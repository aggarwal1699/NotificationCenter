package com.waterwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        setContent { MaterialTheme { WaterWatchApp() } }
    }
}

@Composable
private fun WaterWatchApp() {
    val context = LocalContext.current
    val api = remember { WaterWatchApi(context) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf("Ground Floor") }
    var householdName by remember { mutableStateOf("My Home") }
    var inviteCode by remember { mutableStateOf("") }
    var alerts by remember { mutableStateOf<List<AlertItem>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(api.hasSession()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run(task: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = withContext(Dispatchers.IO) { task() }; loggedIn = api.hasSession(); if (loggedIn) withContext(Dispatchers.IO) { alerts = api.getAlerts() } }
            catch (e: Exception) { message = e.message ?: "Something went wrong" }
            finally { busy = false }
        }
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                scope.launch(Dispatchers.IO) { runCatching { api.saveDeviceToken(token) } }
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("WaterWatch", style = MaterialTheme.typography.headlineLarge) }
            item { Text("घर का पानी ओवरफ्लो अलर्ट", style = MaterialTheme.typography.titleMedium) }
            if (!loggedIn) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(password, { password = it }, label = { Text("Password (min 6 characters)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(floor, { floor = it }, label = { Text("Your floor") }, modifier = Modifier.fillMaxWidth()) }
                item { Button(enabled = !busy, onClick = { run { api.signUp(email, password, name, floor) } }, modifier = Modifier.fillMaxWidth()) { Text("Create account") } }
                item { OutlinedButton(enabled = !busy, onClick = { run { api.signIn(email, password) } }, modifier = Modifier.fillMaxWidth()) { Text("Sign in") } }
                item { Text("Create an account first. Email confirmation may need to be completed before signing in.") }
            } else {
                item { Text("Household setup", style = MaterialTheme.typography.titleLarge) }
                item { OutlinedTextField(householdName, { householdName = it }, label = { Text("Household name") }, modifier = Modifier.fillMaxWidth()) }
                item { Button(enabled = !busy, onClick = { run { api.createHousehold(householdName, floor) } }, modifier = Modifier.fillMaxWidth()) { Text("Create household") } }
                item { OutlinedTextField(inviteCode, { inviteCode = it }, label = { Text("Invitation code") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedButton(enabled = !busy, onClick = { run { api.joinHousehold(inviteCode, floor) } }, modifier = Modifier.fillMaxWidth()) { Text("Join household") } }
                item { Button(enabled = !busy, onClick = { run { alerts = api.getAlerts(); "Alerts refreshed" } }, modifier = Modifier.fillMaxWidth()) { Text("Refresh alerts") } }
                item { Button(enabled = !busy, onClick = { run { api.createAlert() } }, modifier = Modifier.fillMaxWidth().height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("TRIGGER WATER OVERFLOW ALERT", style = MaterialTheme.typography.titleMedium) } }
                item { Text("Alert history", style = MaterialTheme.typography.titleLarge) }
                items(alerts) { alert ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("WATER OVERFLOW • ${alert.status.uppercase()}", style = MaterialTheme.typography.titleMedium)
                            Text("Reported by ${alert.reporterName} • ${alert.floor}")
                            Text(alert.createdAt)
                            alert.responses.forEach { response -> Text("• $response", style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { run { api.respond(alert.id, "motor_off", "Motor switched OFF") } }) { Text("Motor OFF") }
                                TextButton(onClick = { run { api.respond(alert.id, "switching_off", "I am switching it OFF") } }) { Text("Switching OFF") }
                                TextButton(onClick = { run { api.respond(alert.id, "not_my_floor", "Motor not ON from my floor") } }) { Text("Not my floor") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { run { api.respond(alert.id, "not_responsible", "Someone else may have switched it ON") } }) { Text("Not responsible") }
                                TextButton(onClick = { run { api.respond(alert.id, "need_help", "Need assistance") } }) { Text("Need help") }
                            }
                            TextButton(onClick = { run { api.resolve(alert.id) } }) { Text("Mark resolved (admin)") }
                        }
                    }
                }
                item { OutlinedButton(onClick = { api.signOut(); loggedIn = false }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") } }
            }
            if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.primary) }
            if (busy) item { CircularProgressIndicator() }
            item { Text("A family member must notice overflow and press the alert. WaterWatch does not detect water or control the pump.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
