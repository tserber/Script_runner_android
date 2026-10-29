package com.botrunner.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.botrunner.app.data.AppDatabase
import com.botrunner.app.data.BotConfig
import com.botrunner.app.data.SecretsStore
import com.botrunner.app.runner.BotRunnerManager
import com.botrunner.app.runner.BotRunnerService
import com.botrunner.app.runner.BotStatus
import com.botrunner.app.runner.LogBuffer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                BotRunnerRoot()
            }
        }
    }
}

@Composable
fun BotRunnerRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val secrets = remember { SecretsStore(context) }
    val scope = rememberCoroutineScope()

    val bots by db.botDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val states by BotRunnerManager.states.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var logBotId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bot Runner") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add bot")
            }
        }
    ) { padding ->
        if (bots.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No bots yet. Tap + to add one (e.g. examples.telegram_echo_bot).")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
                items(bots, key = { it.id }) { bot ->
                    BotCard(
                        bot = bot,
                        state = states[bot.id],
                        onStart = { BotRunnerService.startBot(context, bot.id) },
                        onStop = { BotRunnerService.stopBot(context, bot.id) },
                        onRestart = { BotRunnerService.restartBot(context, bot.id) },
                        onShowLogs = { logBotId = bot.id },
                        onDelete = {
                            scope.launch {
                                BotRunnerManager.stop(bot.id)
                                db.botDao().delete(bot)
                                secrets.deleteAllForBot(bot.id, bot.envKeys)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        AddBotDialog(
            onDismiss = { showAddDialog = false },
            onSave = { config, secretValues ->
                scope.launch {
                    val id = db.botDao().insert(config)
                    secretValues.forEach { (k, v) -> secrets.set(id, k, v) }
                    showAddDialog = false
                }
            }
        )
    }

    logBotId?.let { id ->
        val bot = bots.firstOrNull { it.id == id }
        if (bot != null) {
            LogDialog(bot = bot, onDismiss = { logBotId = null })
        } else {
            logBotId = null
        }
    }
}

@Composable
private fun BotCard(
    bot: BotConfig,
    state: com.botrunner.app.runner.BotRunState?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onShowLogs: () -> Unit,
    onDelete: () -> Unit
) {
    val status = state?.status ?: BotStatus.STOPPED
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bot.name, style = MaterialTheme.typography.titleMedium)
                    Text(bot.pythonModule, style = MaterialTheme.typography.bodySmall)
                }
                StatusChip(status)
            }
            if (status == BotStatus.CRASHED && state?.lastError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Error: ${state.lastError} (restarts: ${state.restartCount}/${bot.maxRestarts})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                val running = status == BotStatus.RUNNING || status == BotStatus.STARTING
                Button(onClick = onStart, enabled = !running) { Text("Start") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onStop, enabled = running) { Text("Stop") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRestart) { Text("Restart") }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = onShowLogs) { Text("Logs") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun StatusChip(status: BotStatus) {
    val (label, color) = when (status) {
        BotStatus.RUNNING -> "Running" to MaterialTheme.colorScheme.primary
        BotStatus.STARTING -> "Starting" to MaterialTheme.colorScheme.primary
        BotStatus.STOPPING -> "Stopping" to MaterialTheme.colorScheme.secondary
        BotStatus.CRASHED -> "Crashed" to MaterialTheme.colorScheme.error
        BotStatus.STOPPED -> "Stopped" to MaterialTheme.colorScheme.outline
    }
    AssistChip(onClick = {}, label = { Text(label) }, colors = AssistChipDefaults.assistChipColors(labelColor = color))
}

@Composable
private fun AddBotDialog(
    onDismiss: () -> Unit,
    onSave: (BotConfig, Map<String, String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var module by remember { mutableStateOf("") }
    var envKeysText by remember { mutableStateOf("") }
    var autoRestart by remember { mutableStateOf(true) }
    var startOnBoot by remember { mutableStateOf(false) }
    val secretValues = remember { mutableStateMapOf<String, String>() }

    val envKeys = envKeysText.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bot") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    module, { module = it },
                    label = { Text("Python module (e.g. examples.telegram_echo_bot)") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    envKeysText, { envKeysText = it },
                    label = { Text("Secret/env keys, comma separated (e.g. TELEGRAM_TOKEN,CHAT_ID)") },
                    singleLine = true
                )
                envKeys.forEach { key ->
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secretValues[key] ?: "",
                        onValueChange = { secretValues[key] = it },
                        label = { Text(key) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoRestart, { autoRestart = it })
                    Text("Auto-restart on crash")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(startOnBoot, { startOnBoot = it })
                    Text("Start on device boot")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && module.isNotBlank(),
                onClick = {
                    onSave(
                        BotConfig(
                            name = name,
                            pythonModule = module,
                            envKeys = envKeys,
                            autoRestartOnCrash = autoRestart,
                            startOnBoot = startOnBoot
                        ),
                        secretValues.toMap()
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LogDialog(bot: BotConfig, onDismiss: () -> Unit) {
    val lines by LogBuffer.forBot(bot.id).flow.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${bot.name} — logs") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(lines) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
