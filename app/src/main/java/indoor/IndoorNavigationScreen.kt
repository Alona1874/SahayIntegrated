package com.example.pathfinderindoor.indoor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pathfinderindoor.hazard.HazardDetector
import com.example.pathfinderindoor.hazard.HazardListener
import com.example.pathfinderindoor.voice.VoiceManager

private const val PREFS_NAME = "pathfinder_indoor_routes"

private enum class VoiceMode {
    DESTINATION,
    SAVED_ROUTE_CONFIRMATION,
    DIRECTIONS,
    SAVE_CONFIRMATION,
    IDLE
}

private fun listSavedRoutes(context: Context): List<String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return (prefs.getString("route_names", "") ?: "")
        .split("|")
        .filter { it.isNotBlank() }
}

private fun saveRoute(context: Context, route: Route) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val names = listSavedRoutes(context).toMutableList()

    if (!names.contains(route.destinationName)) {
        names.add(route.destinationName)
    }

    prefs.edit()
        .putString("route_names", names.joinToString("|"))
        .putString("route_${route.destinationName}", route.toJson())
        .apply()
}

private fun loadRoute(context: Context, destinationName: String): Route? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val json = prefs.getString("route_$destinationName", null) ?: return null
    return try {
        routeFromJson(json)
    } catch (e: Exception) {
        null
    }
}

private fun findSavedRoute(context: Context, destination: String): Route? {
    val target = destination.lowercase().trim()
    for (name in listSavedRoutes(context)) {
        val saved = name.lowercase().trim()
        if (saved == target || saved.contains(target) || target.contains(saved)) {
            return loadRoute(context, name)
        }
    }
    return null
}

@Composable
fun IndoorNavigationScreen(
    voiceManager: VoiceManager,
    initialDestination: String? = null,
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var destinationName by remember { mutableStateOf(initialDestination?.trim() ?: "") }
    var transcript by remember { mutableStateOf("") }
    var parsedSteps by remember { mutableStateOf<List<Step>>(emptyList()) }
    var currentPrompt by remember { mutableStateOf("Starting indoor voice navigation...") }
    var statusMessage by remember { mutableStateOf("") }
    var isWalking by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(VoiceMode.IDLE) }
    var savedRouteCandidate by remember { mutableStateOf<Route?>(null) }
    var hazardAlert by remember { mutableStateOf("") }
    var hazardBoxesBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentStepDisplay by remember { mutableStateOf("") }

    val pdr = remember { PdrEngine(context) }
    val executor = remember { StepExecutor(pdr) }
    val previewView = remember { PreviewView(context) }

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Hazard Detector setup
    val hazardDetector = remember {
        HazardDetector(
            context = context,
            lifecycleOwner = lifecycleOwner,
            viewFinder = previewView,
            listener = object : HazardListener {
                override fun onHazardDetected(label: String, distanceLevel: String, isUrgent: Boolean) {
                    val alertText = if (isUrgent) "Warning: $label $distanceLevel!" else "$label $distanceLevel"
                    hazardAlert = alertText
                    if (isUrgent) {
                        voiceManager.speakUrgent(alertText)
                    } else {
                        voiceManager.speak(alertText)
                    }
                }

                override fun onBoxesUpdated(bitmap: Bitmap) {
                    hazardBoxesBitmap = bitmap
                }
            }
        )
    }

    // Speech Capture setup
    val speechCapture = remember {
        SpeechCapture(context).apply {
            onListeningStarted = {
                statusMessage = "Listening..."
            }
            onError = { message ->
                statusMessage = message
            }
        }
    }

    fun listen() {
        Handler(Looper.getMainLooper()).post {
            if (!hasMicrophonePermission()) {
                statusMessage = "Microphone permission is required."
                return@post
            }
            statusMessage = "Listening..."
            speechCapture.startListening()
        }
    }

    fun speak(text: String, afterSpeech: (() -> Unit)? = null) {
        currentPrompt = text
        voiceManager.speak(text, afterSpeech)
    }

    fun startRoute(route: Route) {
        parsedSteps = route.steps
        isWalking = true
        voiceMode = VoiceMode.IDLE

        executor.onPrompt = { message ->
            currentStepDisplay = message
            speak(message)
        }

        executor.onRouteComplete = {
            isWalking = false
            hazardDetector.stop()
            voiceMode = VoiceMode.SAVE_CONFIRMATION
            val completionMsg = "You have arrived at $destinationName. Would you like to save this route? Please say save or no."
            speak(completionMsg) {
                listen()
            }
        }

        // Start hazard detection alongside walking if camera permission is available
        if (hasCameraPermission()) {
            try {
                hazardDetector.start()
            } catch (_: Exception) {}
        }

        executor.start(route.steps)
    }

    fun processDestination(speech: String) {
        val destination = SpeechIntentParser.extractDestination(speech)
        if (destination.isBlank()) {
            voiceMode = VoiceMode.DESTINATION
            speak("I could not identify your destination. Please tell me where you would like to go.") {
                listen()
            }
            return
        }

        destinationName = destination
        val savedRoute = findSavedRoute(context, destination)
        savedRouteCandidate = savedRoute

        if (savedRoute != null) {
            voiceMode = VoiceMode.SAVED_ROUTE_CONFIRMATION
            speak("I found a saved route to $destination. Would you like to use it? Please say yes or no.") {
                listen()
            }
        } else {
            voiceMode = VoiceMode.DIRECTIONS
            speak("I do not have a saved route to $destination. Please ask someone nearby for directions. When they are ready, I will listen.") {
                listen()
            }
        }
    }

    fun processCommand(speech: String) {
        val text = speech.lowercase().trim()

        when (voiceMode) {
            VoiceMode.SAVED_ROUTE_CONFIRMATION -> {
                when {
                    SpeechIntentParser.isYes(text) -> {
                        val route = savedRouteCandidate
                        voiceMode = VoiceMode.IDLE
                        if (route != null) {
                            speak("Okay. Starting the saved route to ${route.destinationName}.") {
                                startRoute(route)
                            }
                        }
                    }
                    SpeechIntentParser.isNo(text) -> {
                        voiceMode = VoiceMode.DIRECTIONS
                        speak("Okay. Please ask someone nearby for directions. I will listen.") {
                            listen()
                        }
                    }
                    else -> {
                        speak("I did not understand. Please say yes or no.") {
                            listen()
                        }
                    }
                }
            }

            VoiceMode.SAVE_CONFIRMATION -> {
                when {
                    SpeechIntentParser.wantsToSave(text) -> {
                        saveRoute(context, Route(destinationName, parsedSteps))
                        voiceMode = VoiceMode.IDLE
                        speak("Route saved successfully.")
                    }
                    SpeechIntentParser.doesNotWantToSave(text) -> {
                        voiceMode = VoiceMode.IDLE
                        speak("Okay. The route was not saved.")
                    }
                    else -> {
                        speak("Please say save if you want to save the route, or say no if you do not.") {
                            listen()
                        }
                    }
                }
            }

            VoiceMode.DIRECTIONS -> {
                val steps = DirectionsParser.parse(speech)
                if (steps.isEmpty()) {
                    speak("I could not understand those directions. Please say them again.") {
                        listen()
                    }
                } else {
                    parsedSteps = steps
                    voiceMode = VoiceMode.IDLE
                    speak("I understood the directions. Starting navigation now.") {
                        startRoute(Route(destinationName, steps))
                    }
                }
            }

            VoiceMode.IDLE -> {
                // If walking, check if user said landmark confirmation
                if (isWalking && SpeechIntentParser.isLandmarkReached(text)) {
                    executor.confirmLandmarkReached()
                }
            }

            else -> {}
        }
    }

    // Connect speech recognition results
    LaunchedEffect(voiceMode) {
        speechCapture.onResult = { text ->
            transcript = text
            statusMessage = "Heard: $text"

            when (voiceMode) {
                VoiceMode.DESTINATION -> processDestination(text)
                VoiceMode.SAVED_ROUTE_CONFIRMATION,
                VoiceMode.DIRECTIONS,
                VoiceMode.SAVE_CONFIRMATION -> processCommand(text)
                VoiceMode.IDLE -> {
                    if (isWalking && SpeechIntentParser.isLandmarkReached(text)) {
                        executor.confirmLandmarkReached()
                    }
                }
            }
        }

        speechCapture.onErrorCode = { code, _ ->
            // If user did not speak or speech timed out, prompt gently rather than freezing
            if (code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || code == SpeechRecognizer.ERROR_NO_MATCH) {
                if (voiceMode != VoiceMode.IDLE && !isWalking) {
                    speak("I didn't hear you clearly. Please say again.") {
                        listen()
                    }
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (initialDestination.isNullOrBlank()) {
                voiceMode = VoiceMode.DESTINATION
                speak("Where would you like to go?") {
                    listen()
                }
            } else {
                processDestination(initialDestination)
            }
        } else {
            statusMessage = "Microphone permission is required."
        }
    }

    // Start flow
    LaunchedEffect(Unit) {
        if (hasMicrophonePermission()) {
            if (initialDestination.isNullOrBlank()) {
                voiceMode = VoiceMode.DESTINATION
                speak("Where would you like to go?") {
                    listen()
                }
            } else {
                processDestination(initialDestination)
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Cleanup on screen exit
    DisposableEffect(Unit) {
        onDispose {
            executor.stop()
            speechCapture.destroy()
            pdr.stop()
            try {
                hazardDetector.stop()
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Indoor Navigation",
                style = MaterialTheme.typography.headlineSmall
            )

            if (onExit != null) {
                OutlinedButton(onClick = {
                    executor.stop()
                    pdr.stop()
                    try { hazardDetector.stop() } catch (_: Exception) {}
                    onExit()
                }) {
                    Text("Exit")
                }
            }
        }

        Text(
            text = currentPrompt,
            style = MaterialTheme.typography.bodyLarge
        )

        if (destinationName.isNotBlank()) {
            Text(
                text = "Destination: $destinationName",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (transcript.isNotBlank()) {
            Text(
                text = "Heard: \"$transcript\"",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (statusMessage.isNotBlank()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Camera Preview for Hazard Detection (shown when walking)
        if (isWalking && hasCameraPermission()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                    hazardBoxesBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Hazard Boxes",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (hazardAlert.isNotBlank()) {
                        Text(
                            text = hazardAlert,
                            color = Color.Yellow,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(6.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        if (currentStepDisplay.isNotBlank()) {
            Text(
                text = "Current Step: $currentStepDisplay",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        if (parsedSteps.isNotEmpty()) {
            Text(
                text = "Navigation steps (${parsedSteps.size}):",
                style = MaterialTheme.typography.titleMedium
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(parsedSteps) { step ->
                    Text(text = "• $step")
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // Landmark confirmation button if walking and current step is landmark-based
        if (isWalking) {
            Button(
                onClick = { executor.confirmLandmarkReached() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I've Reached Landmark / Continue")
            }

            Button(
                onClick = {
                    executor.stop()
                    pdr.stop()
                    try { hazardDetector.stop() } catch (_: Exception) {}
                    isWalking = false
                    voiceMode = VoiceMode.IDLE
                    speak("Navigation stopped.")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Stop Navigation")
            }
        } else if (voiceMode == VoiceMode.IDLE && parsedSteps.isEmpty()) {
            Button(
                onClick = {
                    voiceMode = VoiceMode.DESTINATION
                    speak("Where would you like to go?") {
                        listen()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🎤 Speak Destination")
            }
        }
    }
}
