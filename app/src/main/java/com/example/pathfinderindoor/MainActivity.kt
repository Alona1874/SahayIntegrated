package com.example.pathfinderindoor

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pathfinderindoor.hazard.HazardDetector
import com.example.pathfinderindoor.hazard.HazardListener
import com.example.pathfinderindoor.indoor.*
import com.example.pathfinderindoor.ui.theme.PathfinderIndoorTheme
import com.example.pathfinderindoor.voice.VoiceManager

/**
 * State machine for the Voice-First Sahay Assistant.
 */
enum class NavVoiceState {
    INIT,
    HOME_MODE_SELECTION,
    INDOOR_AWAITING_DESTINATION,
    INDOOR_CONFIRM_SAVED_ROUTE,
    INDOOR_AWAITING_DIRECTIONS,
    INDOOR_NAVIGATING,
    INDOOR_SAVE_ROUTE_CONFIRM,
    OUTDOOR_AWAITING_DESTINATION,
    OUTDOOR_NAVIGATING
}

class MainActivity : ComponentActivity() {

    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        voiceManager = VoiceManager(this)

        setContent {
            PathfinderIndoorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        SahayVoiceAssistantApp(voiceManager = voiceManager)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        voiceManager.shutdown()
        super.onDestroy()
    }
}

@Composable
fun SahayVoiceAssistantApp(voiceManager: VoiceManager) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    // Voice State Machine state
    var voiceState by remember { mutableStateOf(NavVoiceState.INIT) }
    var currentPrompt by remember { mutableStateOf("Initializing Sahāy...") }
    var userSpeechHeard by remember { mutableStateOf("") }
    var currentDestination by remember { mutableStateOf("") }
    var currentStepDisplay by remember { mutableStateOf("") }
    var indoorSteps by remember { mutableStateOf<List<Step>>(emptyList()) }
    var savedRouteCandidate by remember { mutableStateOf<Route?>(null) }
    var hazardAlert by remember { mutableStateOf("") }
    var hazardBoxesBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var outdoorLocation by remember { mutableStateOf<Location?>(null) }
    var outdoorGpsStatus by remember { mutableStateOf("GPS ready") }
    var assistantVisualState by remember { mutableStateOf("Speaking...") } // "Listening...", "Speaking...", "Navigating...", "Standing by"

    // Engine instances
    val pdr = remember { PdrEngine(context) }
    val executor = remember { StepExecutor(pdr) }
    val outdoorLocationManager = remember { OutdoorLocationManager(context) }
    val previewView = remember { PreviewView(context) }

    // Hazard Detector setup
    val hazardDetector = remember {
        HazardDetector(
            context = context,
            lifecycleOwner = lifecycleOwner,
            viewFinder = previewView,
            listener = object : HazardListener {
                override fun onHazardDetected(label: String, distanceLevel: String, isUrgent: Boolean) {
                    val alertMsg = if (isUrgent) "Warning: $label $distanceLevel!" else "$label $distanceLevel"
                    hazardAlert = alertMsg
                    if (isUrgent) {
                        voiceManager.speakUrgent(alertMsg)
                    } else {
                        voiceManager.speak(alertMsg)
                    }
                }

                override fun onBoxesUpdated(bitmap: Bitmap) {
                    hazardBoxesBitmap = bitmap
                }
            }
        )
    }

    // Speech Capture
    val speechCapture = remember {
        SpeechCapture(context)
    }

    // Safe speak-then-listen helper
    fun speakAndListen(text: String) {
        currentPrompt = text
        assistantVisualState = "Speaking..."
        speechCapture.cancelListening()

        voiceManager.speak(text) {
            assistantVisualState = "Listening..."
            speechCapture.startListening()
        }
    }

    fun startListeningSafe() {
        if (!voiceManager.isSpeaking) {
            assistantVisualState = "Listening..."
            speechCapture.startListening()
        }
    }

    // Reset to Home state safely
    fun resetToHome(farewell: String = "Returning to main menu.") {
        executor.stop()
        pdr.stop()
        try { hazardDetector.stop() } catch (_: Exception) {}
        speechCapture.cancelListening()
        currentDestination = ""
        currentStepDisplay = ""
        hazardAlert = ""
        hazardBoxesBitmap = null
        indoorSteps = emptyList()
        savedRouteCandidate = null

        voiceState = NavVoiceState.HOME_MODE_SELECTION
        speakAndListen("$farewell Where would you like to go? Say Indoor or Outdoor.")
    }

    // Start indoor navigation execution
    fun startIndoorNavigation(steps: List<Step>) {
        indoorSteps = steps
        voiceState = NavVoiceState.INDOOR_NAVIGATING
        assistantVisualState = "Navigating..."

        executor.onPrompt = { msg ->
            currentStepDisplay = msg
            // Speak prompt, and after speech resume listening for "stop" or "reached"
            currentPrompt = msg
            speechCapture.cancelListening()
            voiceManager.speak(msg) {
                if (voiceState == NavVoiceState.INDOOR_NAVIGATING) {
                    startListeningSafe()
                }
            }
        }

        executor.onRouteComplete = {
            try { hazardDetector.stop() } catch (_: Exception) {}
            voiceState = NavVoiceState.INDOOR_SAVE_ROUTE_CONFIRM
            speakAndListen("You have arrived at $currentDestination. Would you like to save this route? Please say save or no.")
        }

        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasCamera) {
            try { hazardDetector.start() } catch (_: Exception) {}
        }

        executor.start(steps)
    }

    // Start outdoor navigation execution
    fun startOutdoorNavigation(destination: String) {
        currentDestination = destination
        voiceState = NavVoiceState.OUTDOOR_NAVIGATING
        assistantVisualState = "Navigating..."

        outdoorLocationManager.fetchCurrentLocation { loc ->
            outdoorLocation = loc
            if (loc != null) {
                outdoorGpsStatus = "GPS Lock: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
            }
        }

        currentPrompt = "Starting outdoor navigation to $destination."
        voiceManager.speak("Starting outdoor navigation to $destination.") {
            outdoorLocationManager.startWalkingNavigation(destination)
            // Continue listening if user says stop
            startListeningSafe()
        }
    }

    // Handle speech recognized across states
    fun handleSpeechResult(rawText: String) {
        userSpeechHeard = rawText
        val text = SpeechIntentParser.normalize(rawText)

        // Global Stop / Cancel / Back handler
        if (SpeechIntentParser.isStopOrCancel(text)) {
            resetToHome("Navigation cancelled.")
            return
        }

        when (voiceState) {
            NavVoiceState.HOME_MODE_SELECTION -> {
                val mode = SpeechIntentParser.parseMode(text)
                when (mode) {
                    AppNavMode.INDOOR -> {
                        voiceState = NavVoiceState.INDOOR_AWAITING_DESTINATION
                        speakAndListen("Indoor navigation selected. Where would you like to go?")
                    }
                    AppNavMode.OUTDOOR -> {
                        voiceState = NavVoiceState.OUTDOOR_AWAITING_DESTINATION
                        speakAndListen("Outdoor navigation selected. Where would you like to go?")
                    }
                    AppNavMode.NONE -> {
                        val destination = SpeechIntentParser.extractDestination(rawText)
                        if (destination.isNotBlank()) {
                            currentDestination = destination
                            val saved = RouteRepository.findSavedRoute(context, destination)
                            if (saved != null) {
                                savedRouteCandidate = saved
                                voiceState = NavVoiceState.INDOOR_CONFIRM_SAVED_ROUTE
                                speakAndListen("I found a saved route to $destination. Would you like to use it? Please say yes or no.")
                            } else {
                                speakAndListen("You said $destination. Would you like Indoor or Outdoor navigation?")
                            }
                        } else {
                            speakAndListen("Please say Indoor or Outdoor.")
                        }
                    }
                }
            }

            NavVoiceState.INDOOR_AWAITING_DESTINATION -> {
                val mode = SpeechIntentParser.parseMode(text)
                if (mode == AppNavMode.OUTDOOR) {
                    voiceState = NavVoiceState.OUTDOOR_AWAITING_DESTINATION
                    speakAndListen("Switched to Outdoor navigation. Where would you like to go?")
                    return
                }

                val destination = SpeechIntentParser.extractDestination(rawText)
                if (destination.isBlank()) {
                    speakAndListen("I could not identify your destination. Where would you like to go indoors?")
                    return
                }

                currentDestination = destination
                val saved = RouteRepository.findSavedRoute(context, destination)
                if (saved != null) {
                    savedRouteCandidate = saved
                    voiceState = NavVoiceState.INDOOR_CONFIRM_SAVED_ROUTE
                    speakAndListen("I found a saved route to $destination. Would you like to use it? Please say yes or no.")
                } else {
                    voiceState = NavVoiceState.INDOOR_AWAITING_DIRECTIONS
                    speakAndListen("I do not have a saved route to $destination. Please ask someone nearby for directions. When you are ready, please tell me the directions.")
                }
            }

            NavVoiceState.INDOOR_CONFIRM_SAVED_ROUTE -> {
                when {
                    SpeechIntentParser.isYes(text) || SpeechIntentParser.isStartCommand(text) -> {
                        val route = savedRouteCandidate
                        if (route != null) {
                            currentPrompt = "Starting saved route to ${route.destinationName}."
                            voiceManager.speak("Starting saved route to ${route.destinationName}.") {
                                startIndoorNavigation(route.steps)
                            }
                        } else {
                            voiceState = NavVoiceState.INDOOR_AWAITING_DIRECTIONS
                            speakAndListen("Please tell me directions to $currentDestination.")
                        }
                    }
                    SpeechIntentParser.isNo(text) -> {
                        voiceState = NavVoiceState.INDOOR_AWAITING_DIRECTIONS
                        speakAndListen("Okay. Please ask someone nearby for directions. When you are ready, I will listen.")
                    }
                    else -> {
                        speakAndListen("Please say yes to use the saved route, or say no to provide directions.")
                    }
                }
            }

            NavVoiceState.INDOOR_AWAITING_DIRECTIONS -> {
                val parsed = DirectionsParser.parse(rawText)
                if (parsed.isEmpty()) {
                    speakAndListen("I could not understand those directions. Please say them again.")
                } else {
                    currentPrompt = "I understood the directions. Starting navigation now."
                    voiceManager.speak("I understood the directions. Starting navigation now.") {
                        startIndoorNavigation(parsed)
                    }
                }
            }

            NavVoiceState.INDOOR_NAVIGATING -> {
                if (SpeechIntentParser.isLandmarkReached(text)) {
                    executor.confirmLandmarkReached()
                } else {
                    // Continue listening for stop command during navigation
                    startListeningSafe()
                }
            }

            NavVoiceState.INDOOR_SAVE_ROUTE_CONFIRM -> {
                when {
                    SpeechIntentParser.wantsToSave(text) -> {
                        RouteRepository.saveRoute(context, Route(currentDestination, indoorSteps))
                        resetToHome("Route saved successfully.")
                    }
                    SpeechIntentParser.doesNotWantToSave(text) -> {
                        resetToHome("The route was not saved.")
                    }
                    else -> {
                        speakAndListen("Please say save to save this route, or say no.")
                    }
                }
            }

            NavVoiceState.OUTDOOR_AWAITING_DESTINATION -> {
                val mode = SpeechIntentParser.parseMode(text)
                if (mode == AppNavMode.INDOOR) {
                    voiceState = NavVoiceState.INDOOR_AWAITING_DESTINATION
                    speakAndListen("Switched to Indoor navigation. Where would you like to go?")
                    return
                }

                val destination = SpeechIntentParser.extractDestination(rawText)
                if (destination.isBlank()) {
                    speakAndListen("Please tell me your outdoor destination.")
                    return
                }

                startOutdoorNavigation(destination)
            }

            NavVoiceState.OUTDOOR_NAVIGATING -> {
                // If user returned to Sahay and spoke stop/cancel
                if (SpeechIntentParser.isStopOrCancel(text)) {
                    resetToHome("Outdoor navigation ended.")
                } else {
                    startListeningSafe()
                }
            }

            NavVoiceState.INIT -> {}
        }
    }

    // Attach listeners to SpeechCapture
    DisposableEffect(Unit) {
        speechCapture.onListeningStarted = {
            assistantVisualState = "Listening..."
        }
        speechCapture.onResult = { result ->
            handleSpeechResult(result)
        }
        speechCapture.onError = { _ ->
            // If in active navigating, silently re-arm listener
            if (voiceState == NavVoiceState.INDOOR_NAVIGATING || voiceState == NavVoiceState.OUTDOOR_NAVIGATING) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startListeningSafe()
                }, 1000)
            }
        }
        speechCapture.onErrorCode = { code, _ ->
            if (code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || code == SpeechRecognizer.ERROR_NO_MATCH) {
                if (voiceState != NavVoiceState.INDOOR_NAVIGATING && voiceState != NavVoiceState.OUTDOOR_NAVIGATING) {
                    speakAndListen("I didn't hear you. Please say again.")
                }
            }
        }

        onDispose {
            speechCapture.destroy()
            executor.stop()
            pdr.stop()
            try { hazardDetector.stop() } catch (_: Exception) {}
        }
    }

    // Permissions Request & Welcome Initialization
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        voiceState = NavVoiceState.HOME_MODE_SELECTION
        speakAndListen("Welcome to Sahāy. Where would you like to go? Say Indoor or Outdoor.")
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    // Pulsing visual animation for the central voice orb
    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (speechCapture.isListening) 1.15f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val orbColor by animateColorAsState(
        targetValue = when {
            speechCapture.isListening -> MaterialTheme.colorScheme.primary
            voiceManager.isSpeaking -> Color(0xFFF59E0B) // Amber
            voiceState == NavVoiceState.INDOOR_NAVIGATING || voiceState == NavVoiceState.OUTDOOR_NAVIGATING -> Color(0xFF10B981) // Emerald Green
            else -> MaterialTheme.colorScheme.secondary
        },
        label = "orb_color"
    )

    // =========================================================================
    // VOICE-FIRST UI SCREEN
    // =========================================================================
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Sahāy",
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Text(
                text = "Voice-First Navigation Assistant",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Center Voice Orb
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(orbColor.copy(alpha = 0.18f))
                    .border(3.dp, orbColor, CircleShape)
                    .clickable {
                        // Manual toggle fallback if user touches the orb
                        if (speechCapture.isListening) {
                            speechCapture.stopListening()
                        } else {
                            startListeningSafe()
                        }
                    }
            ) {
                Text(
                    text = when {
                        speechCapture.isListening -> "🎤"
                        voiceManager.isSpeaking -> "🔊"
                        voiceState == NavVoiceState.INDOOR_NAVIGATING -> "🚶"
                        voiceState == NavVoiceState.OUTDOOR_NAVIGATING -> "🗺️"
                        else -> "🎙️"
                    },
                    fontSize = 54.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = assistantVisualState,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = orbColor
            )

            if (userSpeechHeard.isNotBlank()) {
                Text(
                    text = "Heard: \"$userSpeechHeard\"",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Main Instruction / Speech Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentPrompt,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 28.sp
                )

                if (currentDestination.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Destination: $currentDestination",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                if (currentStepDisplay.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentStepDisplay,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Indoor Live Hazard Camera Card (shown during active indoor navigation)
        if (voiceState == NavVoiceState.INDOOR_NAVIGATING) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                    hazardBoxesBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Hazard Detection",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (hazardAlert.isNotBlank()) {
                        Text(
                            text = hazardAlert,
                            color = Color.Yellow,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(Color.Black.copy(alpha = 0.75f))
                                .padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Outdoor GPS info (shown during outdoor navigation)
        if (voiceState == NavVoiceState.OUTDOOR_NAVIGATING) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Outdoor Navigation Active",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = outdoorGpsStatus,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Voice Command Hint Box
        Text(
            text = when (voiceState) {
                NavVoiceState.HOME_MODE_SELECTION -> "Say \"Indoor\", \"Outdoor\", or your destination"
                NavVoiceState.INDOOR_AWAITING_DESTINATION,
                NavVoiceState.OUTDOOR_AWAITING_DESTINATION -> "Say your destination, or \"Stop\" to return"
                NavVoiceState.INDOOR_CONFIRM_SAVED_ROUTE -> "Say \"Yes\", \"Start\", or \"No\""
                NavVoiceState.INDOOR_AWAITING_DIRECTIONS -> "Speak directions, or \"Cancel\" to go back"
                NavVoiceState.INDOOR_NAVIGATING -> "Say \"Stop\" anytime to cancel, or \"Reached\" for landmarks"
                NavVoiceState.INDOOR_SAVE_ROUTE_CONFIRM -> "Say \"Save\" or \"No\""
                NavVoiceState.OUTDOOR_NAVIGATING -> "Say \"Stop\" anytime to exit navigation"
                NavVoiceState.INIT -> "Setting up voice navigation..."
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Accessible Fallback Action Buttons (strictly secondary to voice)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (voiceState == NavVoiceState.INDOOR_NAVIGATING || voiceState == NavVoiceState.OUTDOOR_NAVIGATING) {
                Button(
                    onClick = { resetToHome("Navigation stopped.") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Emergency Stop", fontWeight = FontWeight.Bold)
                }

                if (voiceState == NavVoiceState.INDOOR_NAVIGATING) {
                    OutlinedButton(
                        onClick = { executor.confirmLandmarkReached() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Reached Landmark")
                    }
                }
            } else if (voiceState != NavVoiceState.HOME_MODE_SELECTION && voiceState != NavVoiceState.INIT) {
                OutlinedButton(
                    onClick = { resetToHome("Returned to home.") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel / Go Back")
                }
            } else {
                // Home quick-select fallback chips
                OutlinedButton(
                    onClick = { handleSpeechResult("Indoor") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("🏢 Indoor")
                }

                OutlinedButton(
                    onClick = { handleSpeechResult("Outdoor") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("🌲 Outdoor")
                }
            }
        }
    }
}
