package com.orbit.wellness.ui.diet

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.orbit.wellness.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun BarcodeScannerScreen(
    onBarcodeScanned: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }
    var bindError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener(
                    {
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val resolutionSelector = ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build()

                            val analysis = ImageAnalysis.Builder()
                                .setResolutionSelector(resolutionSelector)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            val scanner = BarcodeScanning.getClient()
                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage == null) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees,
                                )
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        val value = barcodes
                                            .asSequence()
                                            .filter {
                                                it.valueType == Barcode.TYPE_PRODUCT ||
                                                    it.format == Barcode.FORMAT_EAN_13 ||
                                                    it.format == Barcode.FORMAT_EAN_8 ||
                                                    it.format == Barcode.FORMAT_UPC_A ||
                                                    it.format == Barcode.FORMAT_UPC_E
                                            }
                                            .mapNotNull { it.rawValue }
                                            .firstOrNull()
                                        if (value != null && handled.compareAndSet(false, true)) {
                                            onBarcodeScanned(value)
                                        }
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        } catch (error: Exception) {
                            bindError = error.message
                        }
                    },
                    ContextCompat.getMainExecutor(ctx),
                )
                previewView
            },
        )

        Button(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.cancel))
        }

        bindError?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }
    }
}
