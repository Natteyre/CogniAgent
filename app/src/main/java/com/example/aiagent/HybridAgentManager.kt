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
import kotlinx.coroutines.withContext

/**
 * Główny Orkiestrator Kognitywny Agenta AI.
 * Łączy lokalny silnik NLU ONNX Runtime, dynamiczny wybór LLM (Chmura/Offline),
 * pamięć podręczną Room SQLite oraz narzędzia systemowe (Tools) w zamkniętą pętlę głosową.
 */
class HybridAgentManager(private val context: Context) {
    private val tag = "HybridAgentManager"
    private val scope = CoroutineScope(Dispatchers.Main)

    // Komponenty danych i automatyzacji sprzętowej
    val database = AgentDatabase.getInstance(context)
    val memoryManager = MemoryManager(context, database)
    val settingsManager = DeviceSettingsManager(context)
    val tools = AgentTools(context)
    val glinerAgent = GlinerAgent(context)

    // Dostawcy modeli językowych (Online i Offline Fallback)
    val onlineClient = OnlineLlmClient()
    val localClient = LocalLlmClient(context)

    // Systemy audio i mowy
    lateinit var speechManager: AgentSpeechManager
    lateinit var ttsManager: AgentTextToSpeechManager

    /**
     * Struktura stanu interfejsu graficznego Material 3
     */
    data class UiState(
        val isListening: Boolean = false,
        val isTtsSpeaking: Boolean = false,
        val lastUserSpeech: String = "",
        val lastAgentResponse: String = "",
        val lastIntent: String = "",
        val activeProviderName: String = "Automatyczny (GLiNER + LLM)",
        val history: List<Pair<String, String>> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        initSpeech()
        loadPersistedConfig()
    }

    private fun initSpeech() {
        ttsManager = AgentTextToSpeechManager(context) { isReady ->
            Log.i(tag, "Inicjalizacja syntezatora TTS: $isReady")
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
                Log.w(tag, "Błąd rozpoznawania mowy: $errorMsg")
            }
        )
    }

    private fun loadPersistedConfig() {
        val prefs = context.getSharedPreferences("cogni_agent_prefs", Context.MODE_PRIVATE)
        onlineClient.apiKey = prefs.getString("llm_api_key", "") ?: ""
        onlineClient.endpointUrl = prefs.getString("llm_endpoint", "https://openai.com") ?: "https://openai.com"
        onlineClient.modelName = prefs.getString("llm_model", "gpt-4o-mini") ?: "gpt-4o-mini"
    }

    fun updateLlmConfig(apiKey: String, endpoint: String, model: String) {
        onlineClient.apiKey = apiKey
        onlineClient.endpointUrl = endpoint
        onlineClient.modelName = model
        context.getSharedPreferences("cogni_agent_prefs", Context.MODE_PRIVATE).edit()
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
     * Centralna Pętla Decyzyjna Agenta (Master Pipeline).
     */
    fun processUserQuery(text: String) {
        if (text.isBlank()) return
        Log.i(tag, "Analiza zapytania użytkownika: \"$text\"")
        addHistory("Użytkownik", text)
        
        scope.launch(Dispatchers.IO) {
            // WYWOŁANIE NOWEGO ASYNCHRONICZNEGO SILNIKA ONNX RUNTIME
            val intent = glinerAgent.predictIntentAsync(text)
            Log.i(tag, "GLiNER Zidentyfikował Intencję: ${intent.intentType}")
            
            _uiState.value = _uiState.value.copy(lastIntent = intent.intentType)
            
            when (intent.intentType) {
                "OPEN_APP" -> {
                    val app = intent.targetApp ?: "aplikacja"
                    val success = AgentAccessibilityService.instance?.executeOpenApp(app)
                        ?: RoutineExecutor(context, database, ttsManager, settingsManager).run {
                            executeActionList(listOf(RoutineAction("OPEN_APP", app)))
                            true
                        }
                    val response = if (success) "Otwieram aplikację $app." else "Nie udało się otworzyć aplikacji $app."
                    respondAndSpeak(response)
                }
                
                "ADJUST_SETTING" -> {
                    val valPercent = intent.settingValue ?: 50
                    if (intent.settingName == "BRIGHTNESS") {
                        val ok = settingsManager.setScreenBrightness(valPercent)
                        val response = if (ok) "Ustawiono jasność ekranu na $valPercent procent." else "Wymagane jest uprawnienie do modyfikacji ustawień systemowych."
                        respondAndSpeak(response)
                    } else {
                        settingsManager.setMediaVolume(valPercent)
                        respondAndSpeak("Ustawiono głośność multimediów na $valPercent procent.")
                    }
                }
                
                "SAVE_FACT" -> {
                    val fact = intent.memoryFact ?: text
                    memoryManager.saveFact("osobiste", fact)
                    respondAndSpeak("Zapisałem w pamięci długoterminowej: $fact")
                }
                
                "QUERY_MEMORY" -> {
                    val query = intent.searchQuery ?: text
                    val facts = memoryManager.searchFacts(query)
                    val response = if (facts.isNotEmpty()) {
                        "Znalazłem w pamięci: " + facts.joinToString("; ") { it.factContent }
                    } else {
                        "Nie znalazłem w mojej bazie danych informacji o: $query."
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
                        respondAndSpeak("Nie podałeś odbiorcy. Do kogo chcesz wysłać ten SMS?")
                    }
                }
                
                "WEB_SEARCH" -> {
                    val query = intent.searchQuery ?: text
                    val searchResults = tools.executeWebSearch(query)
                    val summaryPrompt = "Użytkownik pyta: \"$text\". Wyniki wyszukiwania z sieci:\n$searchResults\nPodsumuj te wyniki zwięźle i odpowiedz naturalnie po polsku."
                    generateLlmResponse(summaryPrompt, injectMemory = false)
                }
                
                else -> {
                    // Pytania ogólne lub złożone operacje narzędziowe trafiają bezpośrednio do LLM
                    generateLlmResponse(text, injectMemory = true)
                }
            }
        }
    }

    private suspend fun generateLlmResponse(prompt: String, injectMemory: Boolean) {
        val memoryContext = if (injectMemory) memoryManager.buildMemoryContext(prompt) else ""
        val systemInstruction = """
            Jesteś CogniAgent - zaawansowanym, lokalnym asystentem głosowym na smartfonie z Androidem.
            Odpowiadaj konkretnie, bezpośrednio i zawsze w języku polskim.
            $memoryContext
        """.trimIndent()
        
        val messages = listOf(
            LlmProvider.Message(role = "system", content = systemInstruction),
            LlmProvider.Message(role = "user", content = prompt)
        )

        // Wybór dostawcy na podstawie łączności i konfiguracji klucza
        val provider: LlmProvider = if (isOnline() && onlineClient.apiKey.isNotBlank()) {
            _uiState.value = _uiState.value.copy(activeProviderName = "Online LLM (${onlineClient.modelName})")
            onlineClient
        } else if (localClient.isModelAvailable()) {
            _uiState.value = _uiState.value.copy(activeProviderName = "Lokalny MediaPipe (Offline)")
            localClient
        } else {
            _uiState.value = _uiState.value.copy(activeProviderName = "Wbudowany silnik heurystyczny")

       respondAndSpeak(generateHeuristicResponse(prompt))
return
}
val response = provider.generateResponse(messages, tools.getToolsJsonDefinition())
if (response.isSuccessful && !response.toolCalls.isNullOrEmpty()) {
// Wywołanie pętli obsługi narzędzi (Function Calling)
handleToolExecution(messages, response.toolCalls)
} else if (response.isSuccessful && !response.text.isNullOrBlank()) {
respondAndSpeak(response.text)
} else {
respondAndSpeak(response.errorMessage ?: "Nie udało się wygenerować odpowiedzi.")
}
}
private suspend fun handleToolExecution(
originalMessages: List<LlmProvider.Message>,
toolCalls: List<LlmProvider.ToolCall>
) {
val updatedMessages = originalMessages.toMutableList()
for (call in toolCalls) {
Log.i(tag, "Uruchamianie narzędzia: ${call.functionName} z parametrami: ${call.argumentsJson}")
val toolResult = try {
val args = JsonParser.parseString(call.argumentsJson).asJsonObject
when (call.functionName) {
"web_search" -> tools.executeWebSearch(args.get("query").asString)
"read_file" -> tools.executeReadFile(args.get("fileName").asString)
"write_file" -> tools.executeWriteFile(args.get("fileName").asString, args.get("content").asString)
"save_user_fact" -> {
memoryManager.saveFact(args.get("category").asString, args.get("fact").asString)
"Fakt został pomyślnie zapisany w pamięci długoterminowej."
}
"open_application" -> {
val opened = AgentAccessibilityService.instance?.executeOpenApp(args.get("appName").asString) ?: false
if (opened) "Aplikacja została pomyślnie uruchomiona." else "Nie udało się odnaleźć aplikacji."
}
"adjust_device_setting" -> {
val setting = args.get("settingName").asString
val value = args.get("valuePercent").asInt
if (setting.equals("BRIGHTNESS", ignoreCase = true)) {
settingsManager.setScreenBrightness(value)
} else {
settingsManager.setMediaVolume(value)
}
"Ustawiono parametetr $setting na $value procent."
}
else -> "Nieznana funkcja narzędziowa: ${call.functionName}"
}
} catch (e: Exception) {
"Błąd wykonania narzędzia ${call.functionName}: ${e.message}"
}
updatedMessages.add(LlmProvider.Message(role = "tool", content = toolResult, toolCallId = call.id))
}
// Odesłanie wyników działania narzędzi z powrotem do LLM w celu syntezy odpowiedzi
val finalResponse = onlineClient.generateResponse(updatedMessages)
respondAndSpeak(finalResponse.text ?: "Operacje systemowe zostały wykonane pomyślnie.")
}
private fun generateHeuristicResponse(prompt: String): String {
val lower = prompt.lowercase()
return when {
lower.contains("kim jeste") -> "Jestem CogniAgent, Twoim lokalnym asystentem głosowym sterującym systemem bez roota."
lower.contains("godzina") || lower.contains("ktra jest") -> {
val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
"Aktualna godzina to $now."
}
else -> "Otrzymałem polecenie: "$prompt". Aby korzystać z pełnej inteligencji, skonfiguruj klucz API w ustawieniach."
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
     
