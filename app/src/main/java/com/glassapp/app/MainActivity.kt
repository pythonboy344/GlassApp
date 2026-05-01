package com.glassapp.app

import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassapp.app.ui.theme.GlassAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private var neuralService: NeuralService? = null
    private var isBound by mutableStateOf(false)
    val snackbarHostState = SnackbarHostState()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NeuralService.LocalBinder
            neuralService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            neuralService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Intent(this, NeuralService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        
        setContent {
            GlassAppTheme {
                if (isBound) {
                    neuralService?.let { service ->
                        GlassHomeScreen(service, snackbarHostState)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

fun View.performHaptic() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassHomeScreen(service: NeuralService, snackbarHostState: SnackbarHostState) {
    val engine = service.engine
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    
    var activeMode by remember { mutableStateOf(engine.activeMode) }
    var currentPreset by remember { mutableStateOf(engine.currentPreset) }
    var dopamineActive by remember { mutableStateOf(engine.dopamineActive) }
    var aiFocusEnabled by remember { mutableStateOf(engine.aiFocusEnabled) }
    var focusScore by remember { mutableDoubleStateOf(1.0) }
    var tuningOffset by remember { mutableDoubleStateOf(0.0) }
    var cooldownMillis by remember { mutableLongStateOf(0L) }
    
    var showPresetSheet by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val filter = IntentFilter("com.glassapp.app.HEADPHONES_REQUIRED")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Headphones Required. High-fidelity entrainment requires isolated stereo channels.",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            activeMode = engine.activeMode
            focusScore = engine.focusScore
            currentPreset = engine.currentPreset
            tuningOffset = engine.tuningOffset
            cooldownMillis = engine.getRemainingCooldownMillis()
            delay(200)
        }
    }

    val controlsAlpha by animateFloatAsState(
        targetValue = if (activeMode == NeuralEngine.SystemMode.FOCUS && focusScore > 0.9) 0.3f else 1.0f,
        animationSpec = tween(2000), label = ""
    )

    if (showPresetSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPresetSheet = false },
            containerColor = Color(0xFF0F0F14),
            scrimColor = Color.Black.copy(alpha = 0.8f),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.DarkGray) }
        ) {
            PresetSelectorContent(
                selectedPreset = currentPreset,
                onPresetSelected = { 
                    view.performHaptic()
                    engine.applyPreset(it)
                    currentPreset = it
                    showPresetSheet = false
                }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A0A0F).copy(alpha = 0.8f), contentColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Bolt, contentDescription = "Engine") },
                    label = { Text("Engine") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AutoGraph, contentDescription = "Benchmark") },
                    label = { Text("Profiles") }
                )
            }
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF050508))
                .padding(padding)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    engine.recordInteraction()
                },
        ) {
            MorphicMeshBackdrop(
                modifier = Modifier.fillMaxSize(),
                widthPx = constraints.maxWidth.toFloat(),
                heightPx = constraints.maxHeight.toFloat(),
                focusScore = focusScore.toFloat(),
                isActive = activeMode != NeuralEngine.SystemMode.OFF
            )

            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(WindowInsets.safeDrawing.asPaddingValues()),
                ) {
                    Header(alpha = controlsAlpha)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        StatusPanel(activeMode, currentPreset, aiFocusEnabled, focusScore, tuningOffset, engine, cooldownMillis)

                        Column(modifier = Modifier.alpha(controlsAlpha), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Focus Panel
                            val isCooling = cooldownMillis > 0
                            GlassPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isCooling) 0.4f else 1.0f)
                                    .clickable(enabled = !isCooling) {
                                        view.performHaptic()
                                        if (activeMode == NeuralEngine.SystemMode.FOCUS) {
                                            service.startService(Intent(service, NeuralService::class.java).apply { action = "STOP" })
                                        } else {
                                            service.startService(Intent(service, NeuralService::class.java).apply { action = "START_FOCUS" })
                                        }
                                    },
                                cornerRadius = 24.dp
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Bolt, contentDescription = null, tint = if (activeMode == NeuralEngine.SystemMode.FOCUS) Color(0xFFEF4444) else Color(0xFF22D3EE))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("FOCUS MODE (40Hz)", color = Color.White, fontWeight = FontWeight.Bold)
                                        if (isCooling) {
                                            Text("Neural Recovery: ${cooldownMillis/1000}s", color = Color.Gray, fontSize = 12.sp)
                                        } else {
                                            Text(if (activeMode == NeuralEngine.SystemMode.FOCUS) "Active" else "Select to Engage", color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            // Healing Panel
                            GlassPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        view.performHaptic()
                                        if (activeMode == NeuralEngine.SystemMode.HEALING) {
                                            service.startService(Intent(service, NeuralService::class.java).apply { action = "STOP" })
                                        } else {
                                            service.startService(Intent(service, NeuralService::class.java).apply { action = "START_HEALING" })
                                        }
                                    },
                                cornerRadius = 24.dp
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Favorite, contentDescription = null, tint = if (activeMode == NeuralEngine.SystemMode.HEALING) Color(0xFF10B981) else Color(0xFF6B5CFF))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("HEALING MODE (Deep Theta)", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(if (activeMode == NeuralEngine.SystemMode.HEALING) "Regenerating" else "Isolated Recovery", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }

                            // Sleep Healing Panel
                            GlassPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        view.performHaptic()
                                        if (activeMode == NeuralEngine.SystemMode.SLEEP_HEALING) {
                                            service.startService(Intent(service, NeuralService::class.java).apply { action = "STOP" })
                                        } else {
                                            service.startService(Intent(service, NeuralService::class.java).apply { action = "START_SLEEP" })
                                        }
                                    },
                                cornerRadius = 24.dp
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.NightsStay, contentDescription = null, tint = if (activeMode == NeuralEngine.SystemMode.SLEEP_HEALING) Color(0xFF93C5FD) else Color(0xFF1E3A8A))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("SLEEP HEALING (Delta)", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(if (activeMode == NeuralEngine.SystemMode.SLEEP_HEALING) "Sleep Entrainment Active" else "Deep Restorative State", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }

                            if (activeMode == NeuralEngine.SystemMode.FOCUS) {
                                GlassPanel(modifier = Modifier.fillMaxWidth().clickable { showPresetSheet = true }, cornerRadius = 24.dp) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.Gray)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text("PRESET", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Text(currentPreset.name, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            if (currentPreset.name == "CUSTOM" && activeMode == NeuralEngine.SystemMode.FOCUS) {
                                CustomLabPanel(engine, view)
                            }

                            TuningPanel(
                                dopamineActive = dopamineActive,
                                onDopamineToggle = { dopamineActive = it; engine.dopamineActive = it },
                                engine = engine,
                                view = view
                            )
                        }

                        if (activeMode != NeuralEngine.SystemMode.OFF) {
                            NeuralLogPanel(engine.focusHistory)
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            } else {
                NeuralBenchmarkScreen(view)
            }
        }
    }
}

@Composable
private fun NeuralBenchmarkScreen(view: View) {
    var benchmarkState by remember { mutableIntStateOf(0) } // 0: Ready, 1: Waiting, 2: Triggered, 3: Result
    var startTime by remember { mutableLongStateOf(0L) }
    var resultTime by remember { mutableLongStateOf(0L) }
    val history = remember { mutableStateListOf<Long>() }
    
    val bgColor by animateColorAsState(
        targetValue = when(benchmarkState) {
            2 -> Color(0xFF10B981)
            1 -> Color(0xFF1F1F2A)
            else -> Color.Transparent
        },
        animationSpec = tween(100), label = ""
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("NEURAL BENCHMARK", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Text("Track cognitive reaction speed", color = Color.Gray)
        
        Spacer(modifier = Modifier.height(64.dp))
        
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(2.dp, Color(0xFF22D3EE).copy(alpha = 0.3f), CircleShape)
                .clickable {
                    view.performHaptic()
                    when (benchmarkState) {
                        0, 3 -> {
                            benchmarkState = 1
                        }
                        1 -> {
                            benchmarkState = 0
                        }
                        2 -> {
                            resultTime = System.currentTimeMillis() - startTime
                            history.add(0, resultTime)
                            if (history.size > 5) history.removeAt(5)
                            benchmarkState = 3
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when(benchmarkState) {
                    0 -> "START"
                    1 -> "WAIT..."
                    2 -> "TAP!"
                    else -> "${resultTime}ms"
                },
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        
        if (benchmarkState == 1) {
            LaunchedEffect(benchmarkState) {
                val randomDelay = (2000..5000).random().toLong()
                delay(randomDelay)
                if (benchmarkState == 1) {
                    startTime = System.currentTimeMillis()
                    benchmarkState = 2
                }
            }
        }

        Spacer(modifier = Modifier.height(64.dp))
        
        if (history.isNotEmpty()) {
            GlassPanel(cornerRadius = 20.dp) {
                Text("LAST 5 TRIALS AVG", color = Color.Gray, fontSize = 12.sp)
                val avg = history.average().toInt()
                Text("${avg}ms", color = Color(0xFF22D3EE), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                history.take(5).forEach { trial: Long ->
                    Text("${trial}ms", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun CustomLabPanel(engine: NeuralEngine, view: View) {
    var baseFreq by remember { mutableDoubleStateOf(engine.customBaseFreq) }
    var beatFreq by remember { mutableDoubleStateOf(engine.customBeatFreq) }
    var isoEnabled by remember { mutableStateOf(engine.customIso) }

    GlassPanel(cornerRadius = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Science, contentDescription = null, tint = Color(0xFF22D3EE), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("NEURAL LABORATORY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Base Carrier", color = Color.Gray, fontSize = 12.sp)
                Text("${baseFreq.toInt()} Hz", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = baseFreq.toFloat(),
                onValueChange = { 
                    if (abs(it - baseFreq.toFloat()) > 2f) view.performHaptic()
                    baseFreq = it.toDouble()
                    engine.updateCustom(baseFreq, beatFreq, isoEnabled)
                },
                valueRange = 60f..400f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF22D3EE))
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Beat Frequency", color = Color.Gray, fontSize = 12.sp)
                Text(String.format(Locale.US, "%.1f Hz", beatFreq), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = beatFreq.toFloat(),
                onValueChange = { 
                    if (abs(it - beatFreq.toFloat()) > 0.5f) view.performHaptic()
                    beatFreq = it.toDouble()
                    engine.updateCustom(baseFreq, beatFreq, isoEnabled)
                },
                valueRange = 0.5f..60f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF22D3EE))
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Isochronic Pulse", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = isoEnabled,
                onCheckedChange = { 
                    view.performHaptic()
                    isoEnabled = it
                    engine.updateCustom(baseFreq, beatFreq, isoEnabled)
                }
            )
        }
    }
}

@Composable
private fun PresetSelectorContent(selectedPreset: Preset, onPresetSelected: (Preset) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("SELECT NEURAL MODE", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Choose your target cognitive state", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(ADVANCED_PRESETS) { preset ->
                val isSelected = preset.name == selectedPreset.name
                val borderAlpha by animateFloatAsState(if (isSelected) 0.6f else 0.1f, label = "")
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) Color(0xFF22D3EE).copy(alpha = 0.05f) else Color.White.copy(alpha = 0.03f))
                        .border(1.dp, Color(0xFF22D3EE).copy(alpha = borderAlpha), RoundedCornerShape(20.dp))
                        .clickable { onPresetSelected(preset) }
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = preset.name, color = if (isSelected) Color(0xFF22D3EE) else Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = preset.description, color = Color.Gray, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF22D3EE), modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(alpha: Float) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).alpha(alpha)) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Text(text = "BIO-FREQUENTIAL SAFETY", style = MaterialTheme.typography.titleSmall, color = Color(0xFF22D3EE), letterSpacing = 2.sp)
    }
}

@Composable
private fun StatusPanel(mode: NeuralEngine.SystemMode, preset: Preset, aiEnabled: Boolean, score: Double, offset: Double, engine: NeuralEngine, cooldown: Long) {
    GlassPanel(cornerRadius = 32.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("SYSTEM STATE", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                Text(when(mode) {
                    NeuralEngine.SystemMode.OFF -> if (cooldown > 0) "RECOVERY" else "IDLE"
                    NeuralEngine.SystemMode.FOCUS -> "FOCUSING"
                    NeuralEngine.SystemMode.HEALING -> "HEALING"
                    NeuralEngine.SystemMode.SLEEP_HEALING -> "SLEEPING"
                }, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (mode != NeuralEngine.SystemMode.OFF) {
                ResonanceVisualizer(score, engine.beatFreq)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        val feedback = when {
            cooldown > 0 -> "Neural refractory period active. Standby."
            mode == NeuralEngine.SystemMode.OFF -> "Systems calibrated. Awaiting engagement."
            mode == NeuralEngine.SystemMode.SLEEP_HEALING -> "Delta entrainment engaged. Deep sleep optimization."
            score > 0.9 -> "Optimal resonance detected. Locking frequency."
            score > 0.7 -> "Stable flow. Minimal drift active."
            score > 0.4 -> "Drift detected. Re-tuning carrier wave..."
            else -> "High agitation. Engaging stabilization."
        }
        
        Text(feedback, color = Color(0xFF22D3EE), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        
        if (mode == NeuralEngine.SystemMode.FOCUS && aiEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Auto-Tune: ${String.format(Locale.US, "%.3f", offset)} Hz", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ResonanceVisualizer(score: Double, beatFreq: Double) {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween((1000 / beatFreq.coerceAtLeast(1.0)).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = ""
    )
    
    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(12.dp).scale(pulse).clip(CircleShape).background(Color(0xFF22D3EE)))
        Box(modifier = Modifier.size(40.dp).scale(pulse * (1f + (1f - score.toFloat()))).clip(CircleShape).background(Color(0xFF22D3EE).copy(alpha = 0.2f)))
    }
}

@Composable
private fun NeuralLogPanel(history: List<Double>) {
    GlassPanel(cornerRadius = 28.dp) {
        Text("NEURAL SYNC HISTORY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))
        
        Canvas(modifier = Modifier.fillMaxWidth().height(60.dp)) {
            if (history.isEmpty()) return@Canvas
            
            val path = Path()
            val stepX = size.width / 20f
            
            history.forEachIndexed { index, score ->
                val x = index * stepX
                val y = size.height * (1f - score.toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            
            drawPath(path, color = Color(0xFF22D3EE), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
private fun TuningPanel(dopamineActive: Boolean, onDopamineToggle: (Boolean) -> Unit, engine: NeuralEngine, view: View) {
    GlassPanel(cornerRadius = 28.dp) {
        Text("BIO-ENGINEERING", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))

        var masterVol by remember { mutableFloatStateOf(engine.masterVolume) }
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SYSTEM INTENSITY", color = Color(0xFF22D3EE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("${(masterVol * 200).toInt()}%", color = Color.White, fontSize = 12.sp)
            }
            Slider(
                value = masterVol,
                onValueChange = { 
                    if (abs(it - masterVol) > 0.05f) view.performHaptic()
                    masterVol = it; engine.masterVolume = it 
                },
                valueRange = 0f..0.5f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF22D3EE), activeTrackColor = Color(0xFF22D3EE))
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(modifier = Modifier.alpha(0.1f), color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        
        var vol by remember { mutableFloatStateOf(engine.dopamineVolume) }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dopamine Layer (15Hz)", color = if (dopamineActive) Color(0xFF22D3EE) else Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = dopamineActive, onCheckedChange = onDopamineToggle)
            }
            if (dopamineActive) {
                Slider(
                    value = vol, 
                    onValueChange = { 
                        if (abs(it - vol) > 0.05f) view.performHaptic()
                        vol = it; engine.dopamineVolume = it 
                    }, 
                    valueRange = 0f..0.3f, 
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF22D3EE), activeTrackColor = Color(0xFF22D3EE))
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        
        var aiFocus by remember { mutableStateOf(engine.aiFocusEnabled) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("AGI Co-Evolution Mode", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = aiFocus, onCheckedChange = { 
                view.performHaptic()
                aiFocus = it; engine.aiFocusEnabled = it 
            })
        }
    }
}

@Composable
private fun MorphicMeshBackdrop(modifier: Modifier, widthPx: Float, heightPx: Float, focusScore: Float, isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = ""
    )

    val baseColor = if (isActive) {
        Color(
            red = (1f - focusScore).coerceIn(0f, 1f),
            green = focusScore.coerceIn(0f, 1f),
            blue = focusScore.coerceIn(0f, 1f),
            alpha = 1f
        )
    } else {
        Color(0xFF6B5CFF)
    }

    Box(modifier = modifier.background(Color(0xFF050508))) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(
            colors = listOf(baseColor.copy(alpha = glowIntensity), Color.Transparent),
            center = Offset(widthPx * 0.2f, heightPx * 0.2f),
            radius = widthPx * 0.8f
        )))
    }
}

@Composable
private fun GlassPanel(modifier: Modifier = Modifier, cornerRadius: Dp = 28.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier = modifier.clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.1f), shape)) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}
