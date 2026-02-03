package com.opnv.inspector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.ExperimentalGetImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import io.jsonwebtoken.Jwts
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InspectorActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var resultCard: CardView
    private lateinit var resultTitle: TextView
    private lateinit var resultDetails: TextView
    private lateinit var resultIcon: ImageView
    private lateinit var cameraExecutor: ExecutorService

    private var publicKey: PublicKey? = null
    private var lastScannedToken: String? = null
    private var lastScanTime: Long = 0
    private var lastFrameWidth: Int = 0
    private var lastFrameHeight: Int = 0
    private var frameCounter: Int = 0
    private val multiFormatReader = MultiFormatReader()

    private val CAMERA_PERMISSION = Manifest.permission.CAMERA
    private val DUPLICATE_SCAN_DELAY = 2000L  // 2 Sekunden

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspector)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        resultCard = findViewById(R.id.resultCard)
        resultTitle = findViewById(R.id.resultTitle)
        resultDetails = findViewById(R.id.resultDetails)
        resultIcon = findViewById(R.id.resultIcon)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ZXing Reader konfigurieren
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.PURE_BARCODE to false,
            DecodeHintType.ASSUME_CODE_39_CHECK_DIGIT to false
        )
        multiFormatReader.setHints(hints)

        Log.d("InspectorActivity", "App started")
        statusText.text = "Initialisiere..."

        // Public Key laden
        loadPublicKey()

        // Kamera-Berechtigungen prüfen
        Log.d("InspectorActivity", "Checking camera permission")
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("InspectorActivity", "Camera permission already granted")
            startCamera()
        } else {
            Log.d("InspectorActivity", "Requesting camera permission")
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERMISSION), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("InspectorActivity", "onRequestPermissionsResult: requestCode=$requestCode, grantResults=${grantResults.contentToString()}")
        
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("InspectorActivity", "Camera permission GRANTED")
                startCamera()
            } else {
                Log.e("InspectorActivity", "Camera permission DENIED")
                statusText.text = getString(R.string.status_permission_denied)
            }
        }
    }

    private fun startCamera() {
        Log.d("InspectorActivity", "startCamera() called")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                Log.d("InspectorActivity", "Getting camera provider")
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            Log.d("InspectorActivity", "Image frame received")
                            processImageProxy(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                Log.d("InspectorActivity", "Camera binding successful")
                statusText.text = getString(R.string.status_ready)
            } catch (exc: Exception) {
                Log.e("InspectorActivity", "Camera Error: ${exc.message}", exc)
                statusText.text = getString(R.string.status_error, exc.message)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val width = mediaImage.width
                val height = mediaImage.height
                val rotation = imageProxy.imageInfo.rotationDegrees
                
                val planes = mediaImage.planes
                val yPlane = planes[0]
                
                // Y-Daten extrahieren
                val yBuffer = yPlane.buffer.duplicate()
                yBuffer.rewind()
                val yData = ByteArray(yBuffer.remaining())
                yBuffer.get(yData)
                
                // Padding entfernen
                val pixelStride = yPlane.pixelStride
                val expectedSize = width * height
                
                // Diagnostics - alle 30 Frames
                frameCounter++
                if (frameCounter % 30 == 0) {
                    Log.d("InspectorActivity", "Frame#$frameCounter size: ${width}x${height}, Rotation: ${rotation}°, PixelStride: $pixelStride")
                    Log.d("InspectorActivity", "YData size: ${yData.size}, Expected: $expectedSize, Actual row size: ${yData.size / height}")
                }
                
                val data = if (yData.size == expectedSize) {
                    Log.v("InspectorActivity", "No padding - using Y data directly")
                    yData
                } else {
                    // Mit Padding - rowSize dynamisch berechnen
                    val rowSize = yData.size / height
                    Log.v("InspectorActivity", "With padding - rowSize=$rowSize, yData.size=${yData.size}")
                    val outData = ByteArray(expectedSize)
                    
                    var outIdx = 0
                    for (y in 0 until height) {
                        val rowStart = y * rowSize
                        for (x in 0 until width) {
                            outData[outIdx++] = yData[rowStart + x * pixelStride]
                        }
                    }
                    outData
                }
                
                try {
                    // Bei 90° Rotation: YUV Daten rotieren
                    val (finalData, finalWidth, finalHeight) = if (rotation == 90) {
                        Log.d("InspectorActivity", "Rotating YUV data 90° CW for ZXing")
                        val rotated = rotateYUV90(data, width, height)
                        Triple(rotated, height, width)
                    } else if (rotation == 270) {
                        Log.d("InspectorActivity", "Rotating YUV data 270° CW for ZXing")
                        val rotated = rotateYUV270(data, width, height)
                        Triple(rotated, height, width)
                    } else {
                        Triple(data, width, height)
                    }
                    
                    // ZXing Erkennung mit rotiertem Frame
                    val source = PlanarYUVLuminanceSource(finalData, finalWidth, finalHeight, 0, 0, finalWidth, finalHeight, false)
                    val binarizer = GlobalHistogramBinarizer(source)
                    val bitmap = BinaryBitmap(binarizer)
                    
                    try {
                        val result = multiFormatReader.decodeWithState(bitmap)
                        if (result != null) {
                            val qrValue = result.text
                            if (qrValue != null && qrValue != lastScannedToken) {
                                Log.d("InspectorActivity", "QR Code detected: $qrValue")
                                lastScannedToken = qrValue
                                lastScanTime = System.currentTimeMillis()
                                verifyToken(qrValue)
                            }
                        }
                    } catch (e: NotFoundException) {
                        Log.v("InspectorActivity", "No barcode in frame")
                    }
                } catch (e: Exception) {
                    Log.e("InspectorActivity", "Decoding error: ${e.javaClass.simpleName} - ${e.message}")
                }
            } else {
                Log.w("InspectorActivity", "MediaImage is null")
            }
        } catch (e: Exception) {
            Log.e("InspectorActivity", "processImageProxy error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun verifyToken(token: String) {
        try {
            if (publicKey == null) {
                showResult(false, getString(R.string.result_invalid), getString(R.string.error_key_not_loaded))
                return
            }

            // Token verifizieren
            val claims = Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .body

            val deviceId = claims["deviceId"] as? String ?: "Unbekannt"
            val sessionId = claims["sessionId"] as? String ?: "Unbekannt"
            val iat = claims["iat"] as? Long ?: 0L

            val datetime = Date(iat * 1000).toString()

            val details = "${getString(R.string.result_device, deviceId)}\n" +
                    "${getString(R.string.result_session, sessionId)}\n" +
                    "${getString(R.string.result_time, datetime)}"

            showResult(true, getString(R.string.result_valid), details)

            Log.d("InspectorActivity", "Token verified - Device: $deviceId, Session: $sessionId")

        } catch (e: Exception) {
            Log.e("InspectorActivity", "Token verification failed: ${e.message}")
            showResult(false, getString(R.string.result_invalid), getString(R.string.result_error, e.message ?: "Unbekannter Fehler"))
        }
    }

    private fun loadPublicKey() {
        try {
            val pemString = resources.openRawResource(R.raw.backend_public)
                .bufferedReader()
                .use { it.readText() }

            // PEM parsen
            val publicKeyPem = pemString
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .trim()

            val decoded = android.util.Base64.decode(publicKeyPem, android.util.Base64.DEFAULT)
            val keyFactory = KeyFactory.getInstance("RSA")
            val keySpec = X509EncodedKeySpec(decoded)
            publicKey = keyFactory.generatePublic(keySpec)

            Log.d("InspectorActivity", "Public key loaded successfully")

        } catch (e: Exception) {
            Log.e("InspectorActivity", "Public key loading failed: ${e.message}")
            statusText.text = "Fehler: Public Key konnte nicht geladen werden (${e.message})"
        }
    }

    private fun showResult(isValid: Boolean, title: String, details: String) {
        runOnUiThread {
            resultTitle.text = title
            resultDetails.text = details

            if (isValid) {
                resultCard.setCardBackgroundColor(
                    ContextCompat.getColor(
                        this,
                        R.color.success_green
                    )
                )
                resultIcon.setImageResource(android.R.drawable.ic_dialog_info)
                resultIcon.setColorFilter(
                    ContextCompat.getColor(this, android.R.color.white),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            } else {
                resultCard.setCardBackgroundColor(
                    ContextCompat.getColor(
                        this,
                        R.color.error_red
                    )
                )
                resultIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                resultIcon.setColorFilter(
                    ContextCompat.getColor(this, android.R.color.white),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }

            resultCard.visibility = android.view.View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun rotateYUV90(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        var idx = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[idx++] = data[y * width + x]
            }
        }
        return rotated
    }

    private fun rotateYUV270(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        var idx = 0
        for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                rotated[idx++] = data[y * width + x]
            }
        }
        return rotated
    }
}
