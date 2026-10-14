package com.example.pathfinderindoor.indoor

import android.Manifest
import android.app.Activity
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.pathfinderindoor.voice.VoiceManager

@Composable
fun OutdoorNavigationScreen(
    voiceManager: VoiceManager,
    initialDestination: String? = null,
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val locationManager = remember { OutdoorLocationManager(context) }

    var destinationText by remember { mutableStateOf(initialDestination?.trim() ?: "") }
    var locationStatus by remember { mutableStateOf("Checking location status...") }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var isListening by remember { mutableStateOf(false) }

    val speechCapture = remember {
        SpeechCapture(context).apply {
            onListeningStarted = {
                locationStatus = "Listening for destination..."
                isListening = true
            }
            onResult = { result ->
                isListening = false
                val extracted = SpeechIntentParser.extractDestination(result)
                if (extracted.isNotBlank()) {
                    destinationText = extracted
                    locationStatus = "Destination: $extracted"
                    voiceManager.speak("Destination set to $extracted. Tap Start Navigation to begin.")
                } else {
                    locationStatus = "Heard: $result"
                    destinationText = result.trim()
                }
            }
            onError = { err ->
                isListening = false
                locationStatus = err
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            locationStatus = "Location permission granted. Acquiring GPS..."
            locationManager.fetchCurrentLocation { loc ->
                currentLocation = loc
                locationStatus = if (loc != null) {
                    "Current Location: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
                } else {
                    "Location active. GPS ready."
                }
            }
        } else {
            locationStatus = "Location permission is required for outdoor navigation."
            voiceManager.speak("Location permission is required for outdoor navigation.")
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            speechCapture.startListening()
        } else {
            locationStatus = "Microphone permission is required."
        }
    }

    fun startOutdoorNav() {
        val dest = destinationText.trim()
        if (dest.isBlank()) {
            voiceManager.speak("Please enter or say your destination first.")
            return
        }

        if (!locationManager.hasLocationPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        locationManager.checkLocationSettings(
            activity = activity,
            requestCode = 200,
            onReady = {
                voiceManager.speak("Starting walking navigation to $dest.") {
                    locationManager.startWalkingNavigation(dest)
                }
            },
            onResolvable = {
                voiceManager.speak("Please enable GPS in system settings.")
            },
            onError = { errorMsg ->
                // Even if high accuracy GPS check failed, still allow launching maps directly
                voiceManager.speak("Opening navigation to $dest.") {
                    locationManager.startWalkingNavigation(dest)
                }
            }
        )
    }

    // Initialize location on startup
    LaunchedEffect(Unit) {
        if (!locationManager.hasLocationPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            locationManager.fetchCurrentLocation { loc ->
                currentLocation = loc
                locationStatus = if (loc != null) {
                    "GPS Active: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
                } else {
                    "GPS ready."
                }
            }
        }

        if (initialDestination.isNullOrBlank()) {
            voiceManager.speak("Outdoor navigation mode. Please say your destination.")
        } else {
            destinationText = initialDestination.trim()
            startOutdoorNav()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechCapture.destroy()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Outdoor Navigation",
                style = MaterialTheme.typography.headlineSmall
            )

            if (onExit != null) {
                OutlinedButton(onClick = onExit) {
                    Text("Exit")
                }
            }
        }

        Text(
            text = "Navigate safely using GPS-assisted walking directions.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "GPS & Location Status",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = locationStatus,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedTextField(
            value = destinationText,
            onValueChange = { destinationText = it },
            label = { Text("Destination") },
            placeholder = { Text("e.g. Kochi, City Center, Library") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isListening
        ) {
            Text(if (isListening) "Listening..." else "🎤 Speak Destination")
        }

        Button(
            onClick = { startOutdoorNav() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Start Outdoor Navigation")
        }
    }
}
