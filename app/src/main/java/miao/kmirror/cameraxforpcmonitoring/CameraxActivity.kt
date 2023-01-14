package miao.kmirror.cameraxforpcmonitoring

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.ktor.server.application.*
import io.ktor.server.application.hooks.CallFailed.install
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import miao.kmirror.cameraxforpcmonitoring.databinding.ActivityCameraxBinding
import miao.kmirror.library.ui.BaseActivity
import miao.kmirror.library.utils.FileUtils
import miao.kmirror.library.utils.ImageUtils
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraxActivity : BaseActivity<ActivityCameraxBinding>() {

    companion object {
        fun startActivity(context: Context) {
            context.startActivity(Intent(context, CameraxActivity::class.java))
        }

        private var imageByteArray: ByteArray? = null
        private const val FILENAME_FORMAT = "yyyy-mm-dd-HH-mm-ss-SSS"
        private var videoQuality = 20
        private var videoSize = Size(720, 1280)
        private const val TAG = "CameraXApp"

        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUEST_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val webSocketServer by lazy {
        embeddedServer(Netty, BuildConfig.Port) {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                get("/") {
                    call.respondText("Hello World")
                }
                webSocket("/chat") {
                    send("You are connected!")
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue
                        val receivedText = frame.readText()
                        send("You said: $receivedText")
                    }
                }
                webSocket("/live") {
                    while (true) {
                        send(Frame.Binary(true, imageByteArray!!))
                    }
                }
            }
        }
    }


    override fun getViewBinding(layoutInflater: LayoutInflater): ActivityCameraxBinding {
        return ActivityCameraxBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (allPermissionGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUEST_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.cardCaptureImage.setOnClickListener {
            takePhoto()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        CoroutineScope(Dispatchers.IO).launch {
            webSocketServer.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        imageCapture = null
        imageByteArray = null
    }

    private fun startCamera() {
        val cameraProviderFeature = ProcessCameraProvider.getInstance(this)
        cameraProviderFeature.addListener({
            val cameraProvider = cameraProviderFeature.get() as ProcessCameraProvider

            //预览
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            // 照相
            imageCapture = ImageCapture.Builder().build()
            // 实时分析
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(videoSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        runBlocking {
                            imageByteArray = ImageUtils.useYuvImageToByteArray(imageProxy, videoQuality)
                        }
                        imageProxy.close()
                    }
                }

            // 选择摄像头
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "startCamera: Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.CHINA)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${FileUtils.getFileFromContentUri(baseContext, outputFileResults.savedUri)}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_LONG).show()
                    Log.d(TAG, "onImageSaved: $msg")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "onError: Photo capture failed: ${exception.message}", exception)
                }

            }
        )

    }

    private fun allPermissionGranted() = REQUEST_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this, "Permissions not granted by the user", Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }
}