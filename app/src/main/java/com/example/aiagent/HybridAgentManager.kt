package com.example.aiagent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Master Cognitive Orchestrator.
 * Connects the GLiNER 2.5 NLU parser, dynamic network-aware LLM providers (Online/Offline),
 * device automation tools, and long-term Room SQLite memory into an event-driven voice loop.
 */
class HybridAgentManager(private val context: Context) {

    private val tag = "HybridAgentManager"
    private val scope = CoroutineScope(Dispatchers.Main)

    // Hardware & Core AI Components
    val database = AgentDatabase.getInstance(context)
    val memoryManager = MemoryManager(context, database)
    val settingsManager = DeviceSettingsManager(context)
    val tools = AgentTools(context)
    val glinerAgent = GlinerAgent(context)

    // LLM Providers
    val onlineClient = OnlineLlmClient()
    val localClient = LocalLlmClient(context)

    // Speech Systems
    lateinit var speechManager: AgentSpeechManager
    lateinit var ttsManager: AgentTextToSpeechManager

    data class UiState(
        val isListening: Boolean = false,
        val isTtsSpeaking: Boolean = false,
        val lastUserSpeech: String = "",
        val lastAgentResponse: String = "",
        val lastIntent: String = "",
        val activeProviderName: String = "Automatyczny (GLiNER + LLM)",
        val history: List<Pair<String, String>> = emptyList() // Pair(Role, Message)
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        initSpeech()
        loadPersistedConfig()
    }

    private fun initSpeech() {
        ttsManager = AgentTextToSpeechManager(context) { isReady ->
            Log.i(tag, "TTS readiness: $isReady")
        }

        speechManager = AgentSpeechManager(
            context = context,
            onResultCallback = { recognizedText ->
                _uiState.value = _uiState.value.copy(
                    isListening = false,
                    lastUserSpeech = recognizedText
                )
                processUserQuery(recognizedText)
            },
            onPartialCallback = { partial ->
                _uiState.value = _uiState.value.copy(lastUserSpeech = partial)
            },
            onErrorCallback = { _, errorMsg ->
                _uiState.value = _uiState.value.copy(isListening = false)
                Log.w(tag, "Speech input error: $errorMsg")
            }
        )
    }

    private fun loadPersistedConfig() {
        val prefs = context.getSharedPreferences("cogni_agent_prefs", Context.MODE_PRIVATE)
        onlineClient.apiKey = prefs.getString("llm_api_key", "") ?: ""
        onlineClient.endpointUrl = prefs.getString("llm_endpoint", "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions"
        onlineClient.modelName = prefs.getString("llm_model", "gpt-4o-mini") ?: "gpt-4o-mini"
    }

    fun updateLlmConfig(apiKey: String, endpoint: String, model: String) {
        onlineClient.apiKey = apiKey
        onlineClient.endpointUrl = endpoint
        onlineClient.modelName = model

        val prefs = context.getSharedPreferences("cogni_agent_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("llm_api_key", apiKey)
            .putString("llm_endpoint", endpoint)
            .putString("llm_model", model)
            .apply()
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun startVoiceListening() {
        _uiState.value = _uiState.value.copy(isListening = true)
        speechManager.startListening(continuous = false)
    }

    fun stopVoiceListening() {
        _uiState.value = _uiState.value.copy(isListening = false)
        speechManager.stopListening()
    }

    /**
     * Central Cognitive Decision Pipeline.
     */
    fun processUserQuery(text: String) {
        if (text.isBlank()) return
        Log.i(tag, "Processing User Input: \"$text\"")

        addHistory("Użytkownik", text)

        scope.launch(Dispatchers.IO) {
            // STEP 1: GLiNER 2.5 NLU Intent Extraction (Asynchronous & Non-blocking)
            val intent = glinerAgent.predictIntentAsync(text)
            Log.i(tag, "GLiNER Classified Intent: ${intent.intentType}")

            _uiState.value = _uiState.value.copy(lastIntent = intent.intentType)

            when (intent.intentType) {
                "OPEN_APP" -> {
                    val app = intent.targetApp ?: "aplikację"
                    val success = AgentAccessibilityService.instance?.executeOpenApp(app)
                        ?: RoutineExecutor(context, database, ttsManager, settingsManager).run {
                            executeActionList(listOf(RoutineAction("OPEN_APP", app)))
                            true
                        }

                    val response = if (success) "Otwieram aplikację $app." else "Nie udało się odnaleźć aplikacji $app."
                    respondAndSpeak(response)
                }

                "ADJUST_SETTING" -> {
                    val valPercent = intent.settingValue ?: 50
                    if (intent.settingName == "BRIGHTNESS") {
                        val ok = settingsManager.setScreenBrightness(valPercent)
                        val response = if (ok) {
                            "Ustawiono jasność ekranu na $valPercent procent."
                        } else {
                            "Wymagane jest uprawnienie do modyfikacji ustawień systemowych."
                        }
                        respondAndSpeak(response)
                    } else {
                        val ok = settingsManager.setMediaVolume(valPercent)
                        respondAndSpeak("Ustawiono głośność multimediów na $valPercent procent.")
                    }
                }

                "SAVE_FACT" -> {
                    val fact = intent.memoryFact ?: text
                    memoryManager.saveFact("osobiste", fact)
                    respondAndSpeak("Zapisałem w pamięci: $fact")
                }

                "QUERY_MEMORY" -> {
                    val query = intent.searchQuery ?: text
                    val facts = memoryManager.searchFacts(query)
                    val response = if (facts.isNotEmpty()) {
                        "Znalazłem w pamięci: " + facts.joinToString("; ") { it.factContent }
                    } else {
                        "Nie znalazłem w pamięci żadnych informacji o: $query."
                    }
                    respondAndSpeak(response)
                }

                "SEND_SMS" -> {
                    val contact = intent.contact ?: ""
                    val message = intent.messageText ?: ""
                    if (contact.isNotBlank()) {
                        respondAndSpeak("Wysyłam SMS do $contact o treści: $message")
                        val executor = RoutineExecutor(context, database, ttsManager, settingsManager)
                        executor.executeActionList(listOf(RoutineAction("SEND_SMS", contact, message)))
                    } else {
                        respondAndSpeak("Do kogo chcesz wysłać wiadomość SMS?")
                    }
                }

                "WEB_SEARCH" -> {
                    val query = intent.searchQuery ?: text
                    val searchResults = tools.executeWebSearch(query)
                    // Summarize search results with LLM
                    val summaryPrompt = "Użytkownik pyta: \"$text\". Wyniki z sieci:\n$searchResults\nOdpowiedz zwięźle i naturalnie po polsku."
                    generateLlmResponse(summaryPrompt, injectMemory = false)
                }

                else -> {
                    // Complex question, conversation, or tool usage -> route to LLM
                    generateLlmResponse(text, injectMemory = true)
                }
            }
        }
    }

    private suspend fun generateLlmResponse(prompt: String, injectMemory: Boolean) {
        val memoryContext = if (injectMemory) memoryManager.buildMemoryContext(prompt) else ""
        val systemInstruction = """
            Jesteś CogniAgent - zaawansowanym, lokalnym asystentem głosowym na smartfonie z Androidem.
            Odpowiadaj konkretnie, bezpośrednio i zawsze po polsku.
            $memoryContext
        """.trimIndent()

        val messages = listOf(
            LlmProvider.Message(role = "system", content = systemInstruction),
            LlmProvider.Message(role = "user", content = prompt)
        )

        // Select optimal provider based on connectivity and configuration
        val provider: LlmProvider = if (isOnline() && onlineClient.apiKey.isNotBlank()) {
            _uiState.value = _uiState.value.copy(activeProviderName = "Online LLM (${onlineClient.modelName})")
            onlineClient
        } else if (localClient.isModelAvailable()) {
            _uiState.value = _uiState.value.copy(activeProviderName = "Lokalny MediaPipe (Offline)")
            localClient
        } else {
            // Heuristic Polish conversational engine if neither is configured
            _uiState.value = _uiState.value.copy(activeProviderName = "Wbudowany silnik heurystyczny")
            val fallbackResponse = generateHeuristicResponse(prompt)
            respondAndSpeak(fallbackResponse)
            return
        }

        val response = provider.generateResponse(messages, tools.getToolsJsonDefinition())

        if (response.isSuccessful && !response.toolCalls.isNullOrEmpty()) {
            // Handle Tool Calls (Function Calling)
            handleToolExecution(messages, response.toolCalls)
        } else if (response.isSuccessful && !response.text.isNullOrBlank()) {
            respondAndSpeak(response.text)
        } else {
            val errorMsg = response.errorMessage ?: "Nie udało się wygenerować odpowiedzi."
            Log.w(tag, "LLM failed: $errorMsg")
            respondAndSpeak(errorMsg)
        }
    }

    private suspend fun handleToolExecution(
        originalMessages: List<LlmProvider.Message>,
        toolCalls: List<LlmProvider.ToolCall>
    ) {
        val updatedMessages = originalMessages.toMutableList()

        for (call in toolCalls) {
            Log.i(tag, "Executing Tool Call: ${call.functionName} with ${call.argumentsJson}")
            val toolResult = try {
                val args = JsonParser.parseString(call.argumentsJson).asJsonObject
                when (call.functionName) {
                    "web_search" -> {
                        val query = args.get("query").asString
                        tools.executeWebSearch(query)
                    }
                    "read_file" -> {
                        val fileName = args.get("fileName").asString
                        tools.executeReadFile(fileName)
                    }
                    "write_file" -> {
                        val fileName = args.get("fileName").asString
                        val content = args.get("content").asString
                        tools.executeWriteFile(fileName, content)
                    }
                    "save_user_fact" -> {
                        val category = args.get("category").asString
                        val fact = args.get("fact").asString
                        memoryManager.saveFact(category, fact)
                        "Fakt został pomyślnie zapisany w pamięci podręcznej."
                    }
                    "open_application" -> {
                        val appName = args.get("appName").asString
                        val opened = AgentAccessibilityService.instance?.executeOpenApp(appName) ?: false
                        if (opened) "Aplikacja $appName została uruchomiona." else "Nie udało się uruchomić $appName."
                    }
                    "adjust_device_setting" -> {
                        val setting = args.get("settingName").asString
                        val value = args.get("valuePercent").asInt
                        if (setting.equals("BRIGHTNESS", ignoreCase = true)) {
                            settingsManager.setScreenBrightness(value)
                        } else {
                            settingsManager.setMediaVolume(value)
                        }
                        "Ustawiono $setting na $value%."
                    }
                    else -> "Nieznana funkcja: ${call.functionName}"
                }
            } catch (e: Exception) {
                "Błąd wykonania narzędzia ${call.functionName}: ${e.message}"
            }

            updatedMessages.add(
                LlmProvider.Message(
                    role = "tool",
                    content = toolResult,
                    toolCallId = call.id
                )
            )
        }

        // Send tool results back to LLM for final natural language synthesis
        val finalResponse = onlineClient.generateResponse(updatedMessages)
        val reply = finalResponse.text ?: "Wykonano żądane operacje."
        respondAndSpeak(reply)
    }

    private fun generateHeuristicResponse(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("kim jesteś") -> "Jestem CogniAgent, Twoim lokalnym asystentem głosowym na telefonie Huawei."
            lower.contains("godzina") || lower.contains("która jest") -> {
                val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                "Aktualna godzina to $now."
            }
            lower.contains("dzień") || lower.contains("jaki mamy dzisiaj") -> {
                val now = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale("pl", "PL")).format(java.util.Date())
                "Dzisiaj jest $now."
            }
            else -> "Otrzymałem Twoje polecenie: \"$prompt\". Aby korzystać z pełnej inteligencji, wprowadź swój klucz API w ustawieniach lub wgraj model lokalny."
        }
    }

    private fun respondAndSpeak(text: String) {
        scope.launch(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(lastAgentResponse = text)
            addHistory("CogniAgent", text)
            ttsManager.speak(text)
        }
    }

    private fun addHistory(role: String, message: String) {
        val current = _uiState.value.history.toMutableList()
        current.add(Pair(role, message))
        if (current.size > 50) current.removeAt(0)
        _uiState.value = _uiState.value.copy(history = current)
    }

    fun destroy() {
        speechManager.destroy()
        ttsManager.shutdown()
        glinerAgent.close()
        localClient.close()
    }
}
