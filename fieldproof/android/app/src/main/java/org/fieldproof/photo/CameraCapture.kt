package org.fieldproof.photo

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.Photo
import org.fieldproof.data.newId
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.io.File

enum class Frame { CARD, DOCUMENT, SCENE }

/**
 * Full-screen in-app camera. Camera only: there is no gallery option. The
 * result is a processed, stamped JPEG with location and time.
 */
@Composable
fun CameraCapture(kind: String, landId: String?, title: String, frame: Frame, onDone: (Photo) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val app = FieldproofApp.instance
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (!it) onCancel() }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) {
        app.gps.acquire()
        onDispose { app.gps.release() }
    }
    val loc by app.gps.lastAnyLocation.collectAsState()

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) CameraBody(kind, landId, frame, onDone)
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(Color(0x66000000), CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = FpType.Headline.copy(color = Color.White))
                    if (loc != null) {
                        Text(stringResource(R.string.camera_location_ok, loc!!.accuracy.toInt()), style = FpType.Helper.copy(color = Color(0xCCFFFFFF)))
                    } else Row {
                        val st = FpType.Helper.copy(color = Color(0xCCFFFFFF))
                        Text(stringResource(R.string.camera_location_wait).trimEnd('…', '.', ' '), style = st)
                        org.fieldproof.ui.components.AnimatedDots(st)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraBody(kind: String, landId: String?, frame: Frame, onDone: (Photo) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val app = FieldproofApp.instance
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val previewView = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            previewView.setOnTouchListener { v, e ->
                if (e.action == android.view.MotionEvent.ACTION_UP) {
                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    v.performClick()
                }
                true
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        if (frame != Frame.SCENE) FrameOverlay(frame)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(if (frame == Frame.CARD) R.string.camera_hint_card else if (frame == Frame.DOCUMENT) R.string.camera_hint_doc else R.string.camera_hint_scene),
                style = FpType.Secondary.copy(color = Color.White),
            )
            Box(
                Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).background(if (busy) Color.Gray else Color.White, CircleShape)
                    .clickable(enabled = !busy) {
                        busy = true
                        val raw = File(ctx.cacheDir, "raw-${System.currentTimeMillis()}.jpg")
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(raw).build(),
                            ContextCompat.getMainExecutor(ctx),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    scope.launch {
                                        val id = newId()
                                        val out = File(app.repo.photoDir, "$id.jpg")
                                        val loc = app.gps.photoLocation()
                                        val p = withContext(Dispatchers.Default) { PhotoProcessing.process(raw, out, loc) }
                                        busy = false
                                        onDone(Photo(id, kind, p.file.absolutePath, "image/jpeg", landId, p.lat, p.lng, p.accuracy, p.capturedAt, p.sharpness, p.readable))
                                    }
                                }
                                override fun onError(exc: ImageCaptureException) { busy = false }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = Fp.Green, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun FrameOverlay(frame: Frame) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width * 0.88f
        val h = if (frame == Frame.CARD) w / 1.586f else w * 1.35f
        val tl = Offset((size.width - w) / 2, (size.height - h) / 2.2f)
        drawRect(Color(0x99000000))
        drawRoundRect(Color.Transparent, tl, Size(w, h), CornerRadius(28f), blendMode = BlendMode.Clear)
        drawRoundRect(Color.White, tl, Size(w, h), CornerRadius(28f), style = Stroke(5f))
    }
}
