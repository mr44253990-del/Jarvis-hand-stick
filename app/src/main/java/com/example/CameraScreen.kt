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
import androidx.compose.ui.geometry.Offset
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
    var scaleFactor by remember { mutableFloatStateOf(1f) }
    var resultBundle by remember { mutableStateOf<ResultBundle?>(null) }
    val backgroundExecutors: ExecutorService = remember { Executors.newSingleThreadExecutor() }

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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
            },
            update = { view ->
                // Calculate scale factor relative to ImageAnalysis size and PreviewView Size
                // Hand Landmarker output is in normalized coordinates (0.0 to 1.0)
                // So width and height can just be multiplied by canvas width and height
            }
        )

        // Draw Skeletal Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val results = resultBundle?.results ?: return@Canvas
            results.landmarks().forEach { landmarks ->
                drawHandSkeleton(landmarks, size.width, size.height)
            }
        }
    }
}

// Draw the hand skeleton connections using MediaPipe HandLandmarker.HAND_CONNECTIONS
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandSkeleton(
    landmarks: List<NormalizedLandmark>,
    width: Float,
    height: Float
) {
    if (landmarks.isEmpty()) return

    // Draw points
    landmarks.forEach { landmark ->
        val x = width - (landmark.x() * width)
        drawCircle(
            color = Color.Red,
            radius = 8f,
            center = Offset(x, landmark.y() * height)
        )
    }

    // Draw lines based on HandLandmarker.HAND_CONNECTIONS
    val connections = HandLandmarker.HAND_CONNECTIONS
    connections.forEach { connection ->
        val start = landmarks[connection.start()]
        val end = landmarks[connection.end()]
        
        val startX = width - (start.x() * width)
        val endX = width - (end.x() * width)

        drawLine(
            color = Color.Green,
            start = Offset(startX, start.y() * height),
            end = Offset(endX, end.y() * height),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
    }
}
