package com.example

import android.Manifest
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.HandLandmarkerHelper.ResultBundle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        CameraPreviewWithGame()
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission is required.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Request Permission")
            }
        }
    }
}

@Composable
fun CameraPreviewWithGame() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backgroundExecutors: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    
    val resultBundle = remember { mutableStateOf<ResultBundle?>(null) }

    val handLandmarkerHelper = remember {
        HandLandmarkerHelper(
            context = context,
            handLandmarkerHelperListener = object : HandLandmarkerHelper.LandmarkerListener {
                override fun onError(error: String) {
                    Log.e("CameraScreen", "HandLandmarker Error: $error")
                }
                override fun onResults(resultBundleData: ResultBundle) {
                    resultBundle.value = resultBundleData
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            handLandmarkerHelper.clearHandLandmarker()
            backgroundExecutors.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Base Camera Layer (Static Composable, won't recompose on every frame)
        AndroidView(
            modifier = Modifier.fillMaxSize().alpha(0f), // Hide camera stream
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also {
                            it.setAnalyzer(backgroundExecutors) { imageProxy ->
                                handLandmarkerHelper.detectLiveStream(imageProxy, true)
                                imageProxy.close()
                            }
                        }

                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraScreen", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // Game Overlay Layer (Isolated recomposition)
        GameOverlay(resultBundle)
    }
}

@Composable
fun GameOverlay(resultBundleState: State<ResultBundle?>) {
    val physicsEngine = remember { PhysicsEngine() }
    var fps by remember { mutableIntStateOf(0) }
    
    // Using produceState for game loop to trigger Canvas redraw independently
    val tick by produceState(initialValue = 0L) {
        var lastTime = System.nanoTime()
        while (true) {
            withFrameNanos { time ->
                value = time // Trigger recomposition of only this block
                val dt = time - lastTime
                if (dt > 10_000_000) { // update fps rarely to prevent jitter
                    val currentFps = (1_000_000_000f / dt).toInt()
                    fps = (fps * 0.9f + currentFps * 0.1f).toInt()
                }
                lastTime = time
                
                val resultBundle = resultBundleState.value
                val canvasWidth = physicsEngine.width
                val canvasHeight = physicsEngine.height
                
                var pointerX = -1f
                var pointerY = -1f
                var pointerZ = -1f
                var isPinching = false

                resultBundle?.results?.landmarks()?.firstOrNull()?.let { landmarks ->
                     val wrist = landmarks[0]
                     val thumb = landmarks[4]
                     val index = landmarks[8]
                     val middleTip = landmarks[12]
                     
                     val tX = canvasWidth - (thumb.x() * canvasWidth)
                     val tY = thumb.y() * canvasHeight
                     val iX = canvasWidth - (index.x() * canvasWidth)
                     val iY = index.y() * canvasHeight

                     val distXY = kotlin.math.hypot(tX - iX, tY - iY)
                     if (distXY < 150f) { 
                         isPinching = true
                     }
                     
                     pointerX = (tX + iX) / 2f
                     pointerY = (tY + iY) / 2f
                     
                     val handDx = wrist.x() - middleTip.x()
                     val handDy = wrist.y() - middleTip.y()
                     val handSize = kotlin.math.hypot(handDx.toDouble(), handDy.toDouble()).toFloat()
                     
                     pointerZ = ((0.5f - handSize) * 2000f).coerceIn(0f, 1000f)
                }

                physicsEngine.update(pointerX, pointerY, pointerZ, isPinching)
            }
        }
    }

    // Drawing Canvas
    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = tick // observe tick to redraw
        physicsEngine.width = size.width
        physicsEngine.height = size.height

        // 3D Infinite background illusion
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF150B2E), Color(0xFF02000A)),
                center = Offset(size.width / 2, size.height / 2),
                radius = size.width
            )
        )

        // Draw Stars
        val random = java.util.Random(42)
        for (i in 0..100) {
            val sx = random.nextFloat() * size.width
            val sy = random.nextFloat() * size.height * 0.5f 
            val sr = random.nextFloat() * 3f + 1f
            drawCircle(color = Color.White.copy(alpha = random.nextFloat() * 0.8f + 0.2f), radius = sr, center = Offset(sx, sy))
        }

        // Draw a cosmic/cyberpunk horizon grid
        val cx = size.width / 2f
        val cy = size.height * 0.45f

        for (i in -20..20) {
            val endX = cx + i * 200f
            drawLine(
                color = Color(0x3300FFFF),
                start = Offset(cx, cy),
                end = Offset(endX, size.height + 200f),
                strokeWidth = 2f
            )
        }
        
        val timeOffset = ((tick / 1_000_000L) * 0.05f) % (size.height / 20f)
        for(i in 0..15) {
            val yProgress = i.toFloat() / 15f
            val yRaw = cy + Math.pow(yProgress.toDouble(), 2.0).toFloat() * (size.height - cy)
            val y = yRaw + (if (i > 0) timeOffset else 0f)
            if (y < size.height && y > cy) {
                val fade = (0.1f + (y - cy) / (size.height - cy)).coerceIn(0f, 1f)
                drawLine(
                    color = Color(0x00FFFF).copy(alpha = 0.4f * fade),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f + fade * 3f
                )
            }
        }

        // Draw Physics Entities
        physicsEngine.draw(this)

        // Draw Skeletal Overlay
        val results = resultBundleState.value?.results ?: return@Canvas
        results.landmarks().forEach { landmarks ->
            drawHandSkeleton(landmarks, size.width, size.height, physicsEngine)
        }
    }

    // HUD Layer
    HUDOverlay(fps = fps, score = 12450, combo = 8)
}

@Composable
fun HUDOverlay(fps: Int, score: Int, combo: Int) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // FPS Counter Top Center
        Text(
            text = "$fps FPS",
            color = if (fps >= 30) Color.Green else Color.Yellow,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha=0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Left HUD Group
        Column(modifier = Modifier.align(Alignment.TopStart).width(160.dp)) {
            // Level Panel
            HudPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("LEVEL 12", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("×", color = Color.Gray, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(2) { Text("⭐", fontSize = 18.sp) }
                    Text("✰", color = Color.Gray, fontSize = 18.sp)
                }
                Divider(color = Color(0x33FFFFFF), modifier = Modifier.padding(vertical = 8.dp))
                
                Text("SCORE", color = Color.LightGray, fontSize = 12.sp)
                Text("12,450", color = Color(0xFFFFC107), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                
                Spacer(modifier = Modifier.height(8.dp))
                Text("TIME", color = Color.LightGray, fontSize = 12.sp)
                Text("1:32", color = Color.White, fontSize = 16.sp)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Combo Panel
            HudPanel {
                Text("COMBO", color = Color.LightGray, fontSize = 12.sp)
                Text("x $combo", color = Color(0xFF00FF00), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Right HUD Group
        Column(modifier = Modifier.align(Alignment.TopEnd).width(180.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HudPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("OBJECTIVES", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("×", color = Color.Gray, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("✅ Collect 15 Cubes", color = Color.LightGray, fontSize = 12.sp)
                Text("✅ Stack 10 Blocks", color = Color.LightGray, fontSize = 12.sp)
                Text("⭕ Throw 5 Objects", color = Color.Gray, fontSize = 12.sp)
            }

            HudPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("NEXT BONUS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("×", color = Color.Gray, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(20.dp).background(Color(0xFFFFA000), RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("x3", color = Color(0xFFFFC107), fontWeight = FontWeight.Bold)
                        Text("Score Multi", color = Color(0xFF00BFFF), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun HudPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22FFFFFF))
            .border(1.dp, Color(0x4400FFFF), RoundedCornerShape(12.dp))
            .padding(12.dp),
        content = content
    )
}


fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandSkeleton(
    landmarks: List<NormalizedLandmark>,
    width: Float,
    height: Float,
    engine: PhysicsEngine
) {
    if (landmarks.isEmpty()) return

    val isGrabbingSomething = engine.isPinching && engine.grabbedObject != null
    val skeletonColor = if (isGrabbingSomething) Color(0xFF00FFCC) else Color(0xFFFFFFFF)
    val pointColor = if (isGrabbingSomething) Color(0xFFFFFFFF) else Color(0xAA00FFFF)

    val connections = HandLandmarker.HAND_CONNECTIONS
    
    // Outer Glow / Translucent Bone Skin
    connections.forEach { connection ->
        val start = landmarks[connection.start()]
        val end = landmarks[connection.end()]
        
        val startX = width - (start.x() * width)
        val endX = width - (end.x() * width)

        drawLine(
            color = Color(0x4400FFFF), 
            start = Offset(startX, start.y() * height),
            end = Offset(endX, end.y() * height),
            strokeWidth = 30f,
            cap = StrokeCap.Round
        )
    }

    // Inner Bone Core
    connections.forEach { connection ->
        val start = landmarks[connection.start()]
        val end = landmarks[connection.end()]
        
        val startX = width - (start.x() * width)
        val endX = width - (end.x() * width)

        drawLine(
            color = skeletonColor,
            start = Offset(startX, start.y() * height),
            end = Offset(endX, end.y() * height),
            strokeWidth = 10f,
            cap = StrokeCap.Round
        )
    }

    // Draw joints
    landmarks.forEach { landmark ->
        val x = width - (landmark.x() * width)
        drawCircle(
            color = pointColor,
            radius = 12f,
            center = Offset(x, landmark.y() * height)
        )
    }
    
    // Draw 3D Grab Target Indicator
    if (engine.isPinching) {
        val t = landmarks[4]
        val i = landmarks[8]
        val pX = (width - (t.x() * width) + width - (i.x() * width)) / 2f
        val pY = (t.y() * height + i.y() * height) / 2f
        
        drawCircle(
            color = if (isGrabbingSomething) Color.Cyan else Color.Yellow,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f),
            radius = 60f,
            center = Offset(pX, pY)
        )
        drawCircle(
            color = if (isGrabbingSomething) Color(0x5500FFFF) else Color(0x55FFFF00),
            radius = 60f,
            center = Offset(pX, pY)
        )
    }
}
