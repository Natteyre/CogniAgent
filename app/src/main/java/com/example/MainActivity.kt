package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.aiagent.*
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var agentManager: HybridAgentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        agentManager = HybridAgentManager(applicationContext)

        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CogniAgentDashboard(
                        agentManager = agentManager,
                        onStartService = {
                            AgentBackgroundEngineService.startService(this)
                        },
                        onStopService = {
                            AgentBackgroundEngineService.stopService(this)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        agentManager.destroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CogniAgentDashboard(
    agentManager: HybridAgentManager,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val uiState by agentManager.uiState.collectAsState()

    var showConfigDialog by remember { mutableStateOf(false) }
    var serviceRunning by remember { mutableStateOf(AgentBackgroundEngineService.isRunning) }

    // System Permission States
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var smsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var writeSettingsGranted by remember { mutableStateOf(agentManager.settingsManager.canModifySystemSettings()) }
    var accessibilityActive by remember { mutableStateOf(AgentAccessibilityService.isServiceRunning) }
    var notificationListenerActive by remember { mutableStateOf(AgentNotificationListener.isConnected) }

    var batteryIgnored by remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }

    // Refresh state periodically or on resume
    DisposableEffect(Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        writeSettingsGranted = agentManager.settingsManager.canModifySystemSettings()
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        accessibilityActive = AgentAccessibilityService.isServiceRunning
        notificationListenerActive = AgentNotificationListener.isConnected
        serviceRunning = AgentBackgroundEngineService.isRunning
        onDispose {}
    }

    // Permission Launchers
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        micGranted = results[Manifest.permission.RECORD_AUDIO] ?: micGranted
        smsGranted = results[Manifest.permission.SEND_SMS] ?: smsGranted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "CogniAgent",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Text("Kirin 980 / LiteRT", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showConfigDialog = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Konfiguracja LLM")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            val fabColor by animateColorAsState(
                if (uiState.isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                label = "fabColor"
            )

            FloatingActionButton(
                onClick = {
                    if (!micGranted) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.READ_SMS
                            )
                        )
                    } else {
                        if (uiState.isListening) {
                            agentManager.stopVoiceListening()
                        } else {
                            agentManager.startVoiceListening()
                        }
                    }
                },
                containerColor = fabColor,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .testTag("voice_fab")
                    .size(64.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (uiState.isListening) "Zatrzymaj nasłuch" else "Rozpocznij mówienie po polsku",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 1. Central Status Card
            item {
                StatusCard(
                    serviceRunning = serviceRunning,
                    activeProvider = uiState.activeProviderName,
                    isListening = uiState.isListening,
                    onToggleService = {
                        if (serviceRunning) {
                            onStopService()
                            serviceRunning = false
                        } else {
                            onStartService()
                            serviceRunning = true
                        }
                    }
                )
            }

            // 2. Active Transcript / Feedback Card
            item {
                TranscriptCard(uiState = uiState)
            }

            // 3. Quick Test Action Chips
            item {
                Text(
                    text = "Szybkie Testy Poleceń Głosowych",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(
                        onClick = { agentManager.processUserQuery("Ustaw głośność na 80%") },
                        label = { Text("Głośność 80%") },
                        icon = { Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("chip_volume")
                    )
                    SuggestionChip(
                        onClick = { agentManager.processUserQuery("Zapamiętaj że mój ulubiony język to Kotlin") },
                        label = { Text("Zapisz fakt") },
                        icon = { Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("chip_memory")
                    )
                }
            }

            // 4. Critical Rootless System Permissions Section
            item {
                Text(
                    text = "Uprawnienia Systemowe (EMUI / Rootless)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Permission: Microphone
            item {
                PermissionRow(
                    title = "Mikrofon (STT)",
                    description = "Niezbędny do nasłuchiwania polskich poleceń głosowych",
                    isGranted = micGranted,
                    onGrantClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.MODIFY_AUDIO_SETTINGS
                            )
                        )
                    }
                )
            }

            // Permission: Accessibility Service
            item {
                PermissionRow(
                    title = "Dostępność (Automatyzacja UI)",
                    description = "Uruchamianie aplikacji i klikanie elementów na ekranie bez roota",
                    isGranted = accessibilityActive,
                    onGrantClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // Permission: Notification Listener
            item {
                PermissionRow(
                    title = "Dostęp do Powiadomień",
                    description = "Przechwytywanie i odczytywanie SMS oraz e-maili w tle",
                    isGranted = notificationListenerActive,
                    onGrantClick = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // Permission: Write System Settings
            item {
                PermissionRow(
                    title = "Modyfikacja Ustawień Systemowych",
                    description = "Umożliwia sterowanie jasnością ekranu i parametrami audio",
                    isGranted = writeSettingsGranted,
                    onGrantClick = {
                        val intent = agentManager.settingsManager.getWriteSettingsIntent()
                        context.startActivity(intent)
                    }
                )
            }

            // Permission: Battery Optimization (Huawei / EMUI)
            item {
                PermissionRow(
                    title = "Ochrona przed ubijaniem (Huawei EMUI)",
                    description = "Wyłącza optymalizację baterii i uśpienie w trybie Doze",
                    isGranted = batteryIgnored,
                    onGrantClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    }
                )
            }

            // 5. Conversation History Log
            item {
                Text(
                    text = "Dziennik Konwersacji i Zdarzeń",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            items(uiState.history.reversed()) { (role, message) ->
                HistoryBubble(role = role, message = message)
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showConfigDialog) {
        LlmConfigDialog(
            currentApiKey = agentManager.onlineClient.apiKey,
            currentEndpoint = agentManager.onlineClient.endpointUrl,
            currentModel = agentManager.onlineClient.modelName,
            onDismiss = { showConfigDialog = false },
            onSave = { apiKey, endpoint, model ->
                agentManager.updateLlmConfig(apiKey, endpoint, model)
                showConfigDialog = false
            }
        )
    }
}

@Composable
fun StatusCard(
    serviceRunning: Boolean,
    activeProvider: String,
    isListening: Boolean,
    onToggleService: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Stan Asystenta Głosowego",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (serviceRunning) "Czuwa w tle (Aktywny)" else "Zatrzymany",
                        color = if (serviceRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }

                Button(
                    onClick = onToggleService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("service_toggle_btn")
                ) {
                    Text(if (serviceRunning) "Zatrzymaj" else "Włącz w tle")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isListening) Color(0xFFFF5252) else Color(0xFF4CAF50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Silnik NLU: GLiNER 2.5 (mDeBERTa-v3 • CPU 4w)",
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2196F3))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Dostawca LLM: $activeProvider",
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun TranscriptCard(uiState: HybridAgentManager.UiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transcript_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Rozpoznana Mowa / Intencja",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (uiState.lastUserSpeech.isNotBlank()) "\"${uiState.lastUserSpeech}\"" else "Kliknij mikrofon i powiedz polecenie w języku polskim...",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            AnimatedVisibility(visible = uiState.lastIntent.isNotBlank()) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Wykryta Intencja: ${uiState.lastIntent}") },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                }
            }

            AnimatedVisibility(visible = uiState.lastAgentResponse.isNotBlank()) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Odpowiedź Asystenta:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiState.lastAgentResponse,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Przyznano",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(28.dp)
                )
            } else {
                OutlinedButton(
                    onClick = onGrantClick,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Nadaj", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun HistoryBubble(role: String, message: String) {
    val isUser = role == "Użytkownik"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = role,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = message, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun LlmConfigDialog(
    currentApiKey: String,
    currentEndpoint: String,
    currentModel: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var endpoint by remember { mutableStateOf(currentEndpoint) }
    var model by remember { mutableStateOf(currentModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konfiguracja LLM & Narzędzi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Klucz API (OpenAI / OpenRouter / Studio)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Adres URL API") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Nazwa Modelu") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Gdy brak połączenia internetowego, asystent automatycznie przełącza się na lokalny silnik MediaPipe / heurystyki.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(apiKey, endpoint, model) }) {
                Text("Zapisz")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anuluj")
            }
        }
    )
}
