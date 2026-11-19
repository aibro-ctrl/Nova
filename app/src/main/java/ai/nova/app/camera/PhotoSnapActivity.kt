package ai.nova.app.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.nova.app.ui.theme.NovaTheme
import java.io.File
import kotlinx.coroutines.delay

/**
 * Фото за человека: Нова сама открывает нужную камеру, отсчитывает пару секунд и снимает —
 * нажимать ничего не нужно. Так работает «сделай селфи» (фронтальная камера) и «сделай фото» (основная).
 * Снимок кладётся в галерею, в папку Pictures/Nova.
 */
class PhotoSnapActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_FRONT = "front"

        fun intent(context: Context, front: Boolean): Intent =
            Intent(context, PhotoSnapActivity::class.java)
                .putExtra(EXTRA_FRONT, front)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val front = intent?.getBooleanExtra(EXTRA_FRONT, false) == true
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent {
            NovaTheme {
                if (granted) {
                    SnapScreen(front = front, onFinish = { message -> finishWith(message) })
                } else {
                    NeedPermission(front = front, onFinish = { message -> finishWith(message) })
                }
            }
        }
    }

    private fun finishWith(message: String) {
        if (message.isNotBlank()) {
            runCatching {
                android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}

@Composable
private fun NeedPermission(front: Boolean, onFinish: (String) -> Unit) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) onFinish("Без разрешения на камеру фото не получится: Настройки → Разрешения → Камера.")
    }
    LaunchedEffect(Unit) { ask.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Разрешите камеру — и я сниму сама", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { onFinish("") }) { Text("Закрыть") }
        }
    }
}

@Composable
private fun SnapScreen(front: Boolean, onFinish: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var bound by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(3) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                        val selector = if (front && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                        val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        val result = runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, selector, preview, imageCapture)
                        }
                        if (result.isSuccess) {
                            capture = imageCapture
                            bound = true
                        } else {
                            ai.nova.core.util.Logger.w("PhotoSnap", "Камера не открылась", result.exceptionOrNull())
                            onFinish("Камера не открылась: " + (result.exceptionOrNull()?.message ?: "нет доступа к камере"))
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        ) {
            Text(
                if (countdown > 0) "Снимаю через $countdown…" else "Снимаю…",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        Text(
            if (front) "Селфи — фронтальная камера" else "Фото — основная камера",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 48.dp),
        )

        LaunchedEffect(capture, bound) {
            val imageCapture = capture?.takeIf { bound } ?: return@LaunchedEffect
            // Даём камере время на фокус и экспозицию: резкое фото важнее мгновенного.
            delay(700)
            for (i in 3 downTo 1) {
                countdown = i
                delay(600)
            }
            countdown = 0
            snap(context, imageCapture) { message -> onFinish(message) }
        }
    }
}

/** Снимает и сохраняет в галерею; о результате сообщает голосом-уведомлением. */
private fun snap(context: Context, imageCapture: ImageCapture, done: (String) -> Unit) {
    val file = File(context.cacheDir, "nova-photo-${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            val message = runCatching { publish(context, file) }
                .map { "Готово: фото в галерее, папка Pictures/Nova." }
                .getOrElse { "Сняла, но сохранить в галерею не удалось." }
            file.delete()
            done(message)
        }

        override fun onError(exception: ImageCaptureException) {
            ai.nova.core.util.Logger.w("PhotoSnap", "Снимок не получился", exception)
            file.delete()
            done("Камера не сработала: ${exception.message ?: "неизвестная ошибка"}. Попробуйте ещё раз.")
        }
    })
}

private fun publish(context: Context, file: File): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nova")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
    return true
}
