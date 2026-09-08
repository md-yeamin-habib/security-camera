package com.example.securitycamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.securitycamera.ui.theme.SecurityCameraTheme
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView

    private val cameraExecutor =
        Executors.newSingleThreadExecutor()

    private val okHttpClient =
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private var webSocket: WebSocket? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                showCamera()
            } else {
                showPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            showCamera()

        } else {

            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    private fun showCamera() {

        setContent {

            SecurityCameraTheme {
                CameraScreen()
            }
        }

        connectToServer()

        startCamera()
    }

    @Composable
    private fun CameraScreen() {

        Box(
            modifier = Modifier.fillMaxSize()
        ) {

            AndroidView(
                factory = { context ->

                    PreviewView(context).also {

                        previewView = it

                        it.scaleType =
                            PreviewView.ScaleType.FILL_CENTER
                    }
                },

                modifier = Modifier.fillMaxSize()
            )

            Text(
                text = "SECURITY CAMERA",

                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
            )
        }
    }

    private fun connectToServer() {

        runOnUiThread {

            Toast.makeText(
                this,
                "Connecting to security server...",
                Toast.LENGTH_SHORT
            ).show()
        }

        val request =
            Request.Builder()
                .url(
                    "wss://red-object-detection.onrender.com/ws/camera"
                )
                .build()

        webSocket =
            okHttpClient.newWebSocket(
                request,

                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response
                    ) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "CONNECTED TO SERVER",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        println(
                            "CONNECTED TO SECURITY SERVER"
                        )
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?
                    ) {

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "CONNECTION FAILED: ${t.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        println(
                            "WEBSOCKET CONNECTION FAILED"
                        )

                        println(
                            "Error: ${t.message}"
                        )

                        t.printStackTrace()
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        println(
                            "WebSocket closed: $code - $reason"
                        )
                    }
                }
            )
    }

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider =
                cameraProviderFuture.get()

            val preview =
                Preview.Builder()
                    .build()

            preview.surfaceProvider =
                previewView.surfaceProvider

            val imageAnalysis =
                ImageAnalysis.Builder()

                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )

                    .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
                    )

                    .build()

            imageAnalysis.setAnalyzer(
                cameraExecutor
            ) { imageProxy ->

                sendFrame(imageProxy)
            }

            val cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA

            try {

                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

            } catch (exception: Exception) {

                exception.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendFrame(
        imageProxy: ImageProxy
    ) {

        try {

            val jpegBytes =
                imageProxyToJpeg(imageProxy)

            val socket =
                webSocket

            if (socket != null) {

                socket.send(
                    ByteString.of(*jpegBytes)
                )
            }

        } catch (exception: Exception) {

            exception.printStackTrace()

        } finally {

            imageProxy.close()
        }
    }

    private fun imageProxyToJpeg(
        image: ImageProxy
    ): ByteArray {

        val yBuffer =
            image.planes[0].buffer

        val uBuffer =
            image.planes[1].buffer

        val vBuffer =
            image.planes[2].buffer

        val ySize =
            yBuffer.remaining()

        val uSize =
            uBuffer.remaining()

        val vSize =
            vBuffer.remaining()

        val nv21 =
            ByteArray(
                ySize + uSize + vSize
            )

        yBuffer.get(
            nv21,
            0,
            ySize
        )

        vBuffer.get(
            nv21,
            ySize,
            vSize
        )

        uBuffer.get(
            nv21,
            ySize + vSize,
            uSize
        )

        val yuvImage =
            YuvImage(
                nv21,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

        val outputStream =
            ByteArrayOutputStream()

        yuvImage.compressToJpeg(
            Rect(
                0,
                0,
                image.width,
                image.height
            ),
            60,
            outputStream
        )

        return outputStream.toByteArray()
    }

    private fun showPermissionDenied() {

        setContent {

            SecurityCameraTheme {

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {

                    Text(
                        text =
                            "Camera permission is required."
                    )
                }
            }
        }
    }

    override fun onDestroy() {

        super.onDestroy()

        webSocket?.close(
            1000,
            "App closed"
        )

        cameraExecutor.shutdown()

        okHttpClient
            .dispatcher
            .executorService
            .shutdown()
    }
}