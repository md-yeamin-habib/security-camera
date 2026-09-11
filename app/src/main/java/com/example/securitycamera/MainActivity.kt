package com.example.securitycamera

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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

    private var serverConnected = false

    private var cameraStarted = false

    private var selectedServerType by mutableStateOf("hosted")

    private var serverAddress by mutableStateOf("")

    private var isConnecting by mutableStateOf(false)

    private var connectionStatus by mutableStateOf("")

    private val preferences: SharedPreferences by lazy {
        getSharedPreferences(
            "security_camera_preferences",
            Context.MODE_PRIVATE
        )
    }


    /*
     * CAMERA PERMISSION
     */

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                if (serverConnected) {
                    startCamera()
                }

            } else {

                showPermissionDenied()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)


        selectedServerType =
            preferences.getString(
                "selected_server_type",
                "hosted"
            ) ?: "hosted"

        showConnectionScreen()
    }


    /*
     * ---------------------------------------------------------
     * CONNECTION SCREEN
     * ---------------------------------------------------------
     */

    private fun showConnectionScreen() {

        setContent {

            SecurityCameraTheme {

                ConnectionScreen(
                    selectedServerType = selectedServerType,
                    serverAddress = serverAddress,
                    isConnecting = isConnecting,
                    connectionStatus = connectionStatus,
                    history = getCurrentHistory(),

                    onServerTypeChange = { type ->

                        selectedServerType = type

                        serverAddress = ""

                        connectionStatus = ""

                        preferences.edit()
                            .putString(
                                "selected_server_type",
                                type
                            )
                            .apply()
                    },

                    onAddressChange = { value ->

                        serverAddress = value

                        connectionStatus = ""
                    },

                    onSuggestionSelected = { value ->

                        serverAddress = value

                        connectionStatus = ""
                    },

                    onSuggestionDeleted = { value ->

                        deleteHistoryEntry(value)
                    },

                    onConnect = {

                        connectToServer()
                    }
                )
            }
        }
    }


    /*
     * ---------------------------------------------------------
     * HISTORY
     * ---------------------------------------------------------
     */

    private fun getCurrentHistory(): List<String> {

        val key =
            if (selectedServerType == "hosted") {
                "hosted_history"
            } else {
                "local_history"
            }

        val orderedKey =
            if (selectedServerType == "hosted") {
                "hosted_history_ordered"
            } else {
                "local_history_ordered"
            }


        val ordered: String =
            preferences.getString(
                orderedKey,
                ""
            ) ?: ""

        if (ordered.isBlank()) {

            val historySet =
                preferences.getStringSet(
                    key,
                    emptySet()
                ) ?: emptySet()

            return historySet.toList()
        }

        return ordered
            .split("|")
            .filter { it.isNotBlank() }
    }


    private fun saveHistoryEntry(value: String) {

        val cleanValue =
            value.trim()

        if (cleanValue.isEmpty()) {
            return
        }

        val key =
            if (selectedServerType == "hosted") {
                "hosted_history_ordered"
            } else {
                "local_history_ordered"
            }

        val storedHistory: String =
            preferences.getString(
                key,
                ""
            ) ?: ""

        val existing =
            if (storedHistory.isBlank()) {

                mutableListOf()

            } else {

                storedHistory
                    .split("|")
                    .filter { it.isNotBlank() }
                    .toMutableList()
            }


        existing.removeAll {
            it.equals(
                cleanValue,
                ignoreCase = true
            )
        }



        existing.add(
            0,
            cleanValue
        )


        val limited =
            existing.take(5)


        preferences.edit()
            .putString(
                key,
                limited.joinToString("|")
            )
            .apply()
    }


    private fun deleteHistoryEntry(value: String) {

        val key =
            if (selectedServerType == "hosted") {
                "hosted_history_ordered"
            } else {
                "local_history_ordered"
            }

        val storedHistory: String =
            preferences.getString(
                key,
                ""
            ) ?: ""

        val existing =
            if (storedHistory.isBlank()) {

                mutableListOf()

            } else {

                storedHistory
                    .split("|")
                    .filter { it.isNotBlank() }
                    .toMutableList()
            }


        existing.removeAll {
            it == value
        }


        preferences.edit()
            .putString(
                key,
                existing.joinToString("|")
            )
            .apply()

        showConnectionScreen()
    }


    /*
     * ---------------------------------------------------------
     * CONNECT TO SERVER
     * ---------------------------------------------------------
     */

    private fun connectToServer() {

        val address =
            serverAddress.trim()


        if (address.isEmpty()) {

            connectionStatus =
                if (selectedServerType == "hosted") {
                    "Please enter a domain name."
                } else {
                    "Please enter the local server IP."
                }

            return
        }


        saveHistoryEntry(address)

        val websocketUrl =
            if (selectedServerType == "hosted") {

                "wss://$address/ws/camera"

            } else {

                "ws://$address:8000/ws/camera"
            }


        webSocket?.close(
            1000,
            "Opening new connection"
        )

        webSocket = null

        serverConnected = false

        cameraStarted = false

        isConnecting = true

        connectionStatus =
            "Connecting to server..."


        val request =
            try {

                Request.Builder()
                    .url(websocketUrl)
                    .build()

            } catch (exception: IllegalArgumentException) {

                isConnecting = false

                connectionStatus =
                    "Invalid server address."

                return
            }


        webSocket =
            okHttpClient.newWebSocket(
                request,

                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response
                    ) {

                        serverConnected = true

                        isConnecting = false

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "CONNECTED TO SERVER",
                                Toast.LENGTH_SHORT
                            ).show()


                            if (
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.CAMERA
                                ) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {

                                startCamera()

                            } else {

                                cameraPermissionLauncher.launch(
                                    Manifest.permission.CAMERA
                                )
                            }
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

                        serverConnected = false

                        isConnecting = false

                        runOnUiThread {

                            connectionStatus =
                                "Connection failed."

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
                    }


                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        serverConnected = false

                        println(
                            "WebSocket closed: $code - $reason"
                        )
                    }
                }
            )
    }


    /*
     * ---------------------------------------------------------
     * CONNECTION UI
     * ---------------------------------------------------------
     */

    @Composable
    private fun ConnectionScreen(
        selectedServerType: String,
        serverAddress: String,
        isConnecting: Boolean,
        connectionStatus: String,
        history: List<String>,
        onServerTypeChange: (String) -> Unit,
        onAddressChange: (String) -> Unit,
        onSuggestionSelected: (String) -> Unit,
        onSuggestionDeleted: (String) -> Unit,
        onConnect: () -> Unit
    ) {

        val query =
            serverAddress.trim()

        val suggestions =
            if (query.isEmpty()) {

                emptyList()

            } else {

                history
                    .filter {
                        it.contains(
                            query,
                            ignoreCase = true
                        )
                    }
                    .take(10)
            }


        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Center
        ) {

            Text(
                text = "⚙  Connection Settings",
                modifier = Modifier
                    .fillMaxWidth(),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(28.dp)
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onServerTypeChange(
                                "hosted"
                            )
                        },

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                RadioButton(
                    selected =
                        selectedServerType == "hosted",

                    onClick = {

                        onServerTypeChange(
                            "hosted"
                        )
                    }
                )

                Spacer(
                    modifier =
                        Modifier.width(8.dp)
                )

                Text(
                    text = "Hosted Server"
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onServerTypeChange(
                                "local"
                            )
                        },

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                RadioButton(
                    selected =
                        selectedServerType == "local",

                    onClick = {

                        onServerTypeChange(
                            "local"
                        )
                    }
                )

                Spacer(
                    modifier =
                        Modifier.width(8.dp)
                )

                Text(
                    text = "Local Server"
                )
            }


            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )


            Text(
                text =
                    if (
                        selectedServerType == "hosted"
                    ) {
                        "Enter Domain Name"
                    } else {
                        "Enter Local Server IP"
                    },

                modifier =
                    Modifier.fillMaxWidth()
            )


            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            OutlinedTextField(

                value =
                    serverAddress,

                onValueChange =
                    onAddressChange,

                modifier =
                    Modifier.fillMaxWidth(),

                singleLine = true,

                placeholder = {

                    Text(
                        text =
                            if (
                                selectedServerType ==
                                "hosted"
                            ) {

                                "red-object-detection.onrender.com"

                            } else {

                                "192.168.1.2"
                            }
                    )
                },

                keyboardOptions =
                    KeyboardOptions(
                        imeAction =
                            ImeAction.Done
                    ),

                keyboardActions =
                    KeyboardActions(
                        onDone = {

                            if (!isConnecting) {
                                onConnect()
                            }
                        }
                    ),

                enabled =
                    !isConnecting
            )


            if (suggestions.isNotEmpty()) {

                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )


                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Color.White,
                                RoundedCornerShape(8.dp)
                            )
                ) {

                    suggestions.forEach { suggestion ->

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 12.dp,
                                        end = 4.dp
                                    ),

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Text(
                                text =
                                    suggestion,

                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .clickable {

                                            onSuggestionSelected(
                                                suggestion
                                            )
                                        }
                                        .padding(
                                            vertical = 12.dp
                                        )
                            )


                            TextButton(
                                onClick = {

                                    onSuggestionDeleted(
                                        suggestion
                                    )
                                }
                            ) {

                                Text(
                                    text = "×"
                                )
                            }
                        }
                    }
                }
            }


            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )


            Button(
                onClick = onConnect,

                enabled =
                    !isConnecting
            ) {

                if (isConnecting) {

                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(20.dp),

                        strokeWidth = 2.dp
                    )

                    Spacer(
                        modifier =
                            Modifier.width(10.dp)
                    )
                }


                Text(
                    text =
                        if (isConnecting) {
                            "CONNECTING..."
                        } else {
                            "CONNECT"
                        }
                )
            }


            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )


            if (connectionStatus.isNotBlank()) {

                Text(
                    text =
                        connectionStatus
                )
            }
        }
    }


    /*
     * ---------------------------------------------------------
     * CAMERA
     * ---------------------------------------------------------
     */

    private fun startCamera() {

        if (cameraStarted) {
            return
        }

        cameraStarted = true


        runOnUiThread {

            setContent {

                SecurityCameraTheme {

                    CameraScreen()
                }
            }
        }


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

                cameraStarted = false

                exception.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }


    @Composable
    private fun CameraScreen() {

        Box(
            modifier =
                Modifier.fillMaxSize()
        ) {

            AndroidView(
                factory = { context: Context ->

                    PreviewView(context).also {

                        previewView = it

                        it.scaleType =
                            PreviewView.ScaleType.FILL_CENTER
                    }
                },

                modifier =
                    Modifier.fillMaxSize()
            )


            Text(
                text =
                    "SECURITY CAMERA",

                modifier =
                    Modifier
                        .align(
                            Alignment.TopCenter
                        )
                        .padding(
                            top = 40.dp
                        )
            )
        }
    }


    /*
     * ---------------------------------------------------------
     * SEND FRAME
     * ---------------------------------------------------------
     */

    private fun sendFrame(
        imageProxy: ImageProxy
    ) {

        try {

            /*
             * Don't process/send frames if the
             * WebSocket is no longer connected.
             */

            if (
                !serverConnected ||
                webSocket == null
            ) {
                return
            }


            val jpegBytes =
                imageProxyToJpeg(
                    imageProxy
                )


            webSocket?.send(
                ByteString.of(
                    *jpegBytes
                )
            )

        } catch (exception: Exception) {

            exception.printStackTrace()

        } finally {

            imageProxy.close()
        }
    }


    /*
     * ---------------------------------------------------------
     * IMAGE → JPEG
     * ---------------------------------------------------------
     */

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
                ySize +
                        uSize +
                        vSize
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


    /*
     * ---------------------------------------------------------
     * PERMISSION DENIED
     * ---------------------------------------------------------
     */

    private fun showPermissionDenied() {

        setContent {

            SecurityCameraTheme {

                Box(
                    modifier =
                        Modifier.fillMaxSize(),

                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        text =
                            "Camera permission is required."
                    )
                }
            }
        }
    }


    /*
     * ---------------------------------------------------------
     * CLEANUP
     * ---------------------------------------------------------
     */

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