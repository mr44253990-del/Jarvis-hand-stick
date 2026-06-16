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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
    
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status.isGranted) {
        CameraPreviewWithSkeleton()
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(text = "Camera permission is required", color = Color.White)
        }
    }
}

@Composable
fun CameraPreviewWithSkeleton() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var handLandmarkerHelper by remember { mutableStateOf<HandLandmarkerHelper?>(null) }
    var resultBundle by remember { mutableStateOf<ResultBundle?>(null) }
    val backgroundExecutors: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    
    val physicsEngine = remember { PhysicsEngine() }
    var frameCounter by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        handLandmarkerHelper = HandLandmarkerHelper(
            context = context,
            handLandmarkerHelperListener = object : HandLandmarkerHelper.LandmarkerListener {
                override fun onError(error: String) {
                    Log.e("CameraScreen", "HandLandmarker Error: $error")
                }

                override fun onResults(result: ResultBundle) {
                    resultBundle = result
                }
            }
        )
        onDispose {
            handLandmarkerHelper?.clearHandLandmarker()
            backgroundExecutors.shutdown()
        }
    }
    
    LaunchedEffect(Unit) {
        while(true) {
            withFrameNanos {
                val canvasWidth = physicsEngine.width
                val canvasHeight = physicsEngine.height
                
                var pointerX = -1f
                var pointerY = -1f
                var isPinching = false

                resultBundle?.results?.landmarks()?.firstOrNull()?.let { landmarks ->
                     val thumb = landmarks[4]
                     val index = landmarks[8]
                     
                     val tX = canvasWidth - (thumb.x() * canvasWidth)
                     val tY = thumb.y() * canvasHeight
                     val iX = canvasWidth - (index.x() * canvasWidth)
                     val iY = index.y() * canvasHeight

                     val dist = kotlin.math.hypot(tX - iX, tY - iY)
                     if (dist < 150f) { // threshold for pinch
                         isPinching = true
                     }
                     
                     pointerX = (tX + iX) / 2f
                     pointerY = (tY + iY) / 2f
                }

                physicsEngine.update(pointerX, pointerY, isPinching)
                frameCounter++
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(backgroundExecutors) { image ->
                                handLandmarkerHelper?.detectLiveStream(
                                    imageProxy = image,
                                    isFrontCamera = true
                                )
                                image.close()
                            }
                        }

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()

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

        val tempFrame = frameCounter
        Canvas(modifier = Modifier.fillMaxSize()) {
            physicsEngine.width = size.width
            physicsEngine.height = size.height

            // 3D Infinite background illusion
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF2C3E50), Color(0xFF0A0F1A)),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = size.width
                )
            )

            // Draw Physics Entities
            physicsEngine.draw(this)

            // Draw Skeletal Overlay
            val results = resultBundle?.results ?: return@Canvas
            results.landmarks().forEach { landmarks ->
                drawHandSkeleton(landmarks, size.width, size.height, physicsEngine)
            }
        }
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandSkeleton(
    landmarks: List<NormalizedLandmark>,
    width: Float,
    height: Float,
    engine: PhysicsEngine
) {
    if (landmarks.isEmpty()) return

    // Special color if pinching!
    val isGrabbingSomething = engine.isPinching && engine.grabbedObject != null
    val skeletonColor = if (isGrabbingSomething) Color.Cyan else Color.White
    val pointColor = if (isGrabbingSomething) Color.White else Color(0xAAFFFFFF)

    // Draw lines base on connections
    val connections = HandLandmarker.HAND_CONNECTIONS
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

    // Draw points
    landmarks.forEach { landmark ->
        val x = width - (landmark.x() * width)
        drawCircle(
            color = pointColor,
            radius = 12f,
            center = Offset(x, landmark.y() * height)
        )
    }
    
    // Draw pinch indicator at the pinch center
    if (engine.isPinching) {
        val t = landmarks[4]
        val i = landmarks[8]
        val pX = (width - (t.x() * width) + width - (i.x() * width)) / 2f
        val pY = (t.y() * height + i.y() * height) / 2f
        
        drawCircle(
            color = Color.Yellow.copy(alpha = 0.6f),
            radius = 40f,
            center = Offset(pX, pY)
        )
    }
}
