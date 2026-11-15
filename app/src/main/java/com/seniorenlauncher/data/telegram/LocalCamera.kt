package com.seniorenlauncher.data.telegram

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors

/**
 * Our camera for Telegram video calls. NTgCalls' own camera source never reports its frames, so
 * there would be no self-view: instead we capture with CameraX, hand each frame (I420) to NTgCalls
 * as an EXTERNAL camera source, and show the same frame in the self-view.
 *
 * [onFrame] gets (i420, width, height, rotationDegrees) on a background thread.
 */
class LocalCamera(
    private val context: Context,
    private val onFrame: (ByteArray, Int, Int, Int) -> Unit
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tg-call-camera").apply { isDaemon = true } }
    private var provider: ProcessCameraProvider? = null
    private var front = true
    @Volatile private var stopped = false

    fun start(frontCamera: Boolean) = main.post {
        front = frontCamera
        registry.currentState = Lifecycle.State.RESUMED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            provider = try { future.get() } catch (e: Exception) { Log.e(TAG, "camera provider", e); null }
            bind()
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchTo(frontCamera: Boolean) = main.post {
        front = frontCamera
        bind()
    }

    fun stop() {
        stopped = true
        main.post {
            try { provider?.unbindAll() } catch (_: Exception) {}
            registry.currentState = Lifecycle.State.DESTROYED
            worker.shutdown()
        }
    }

    private fun bind() {
        val p = provider ?: return
        if (stopped) return
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(worker) { img ->
            try {
                if (!stopped) onFrame(toI420(img), img.width, img.height, img.imageInfo.rotationDegrees)
            } catch (e: Exception) {
                Log.w(TAG, "frame", e)
            } finally {
                img.close()
            }
        }
        try {
            p.unbindAll()
            val selector = if (front && p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
            p.bindToLifecycle(this, selector, analysis)
        } catch (e: Exception) {
            Log.e(TAG, "bind camera", e)
        }
    }

    /** YUV_420_888 (any row/pixel stride) -> tightly packed I420. */
    private fun toI420(img: ImageProxy): ByteArray {
        val w = img.width; val h = img.height
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        val out = ByteArray(w * h + 2 * cw * ch)
        val planes = img.planes
        var o = 0
        // Y
        val y = planes[0].buffer; val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        for (r in 0 until h) {
            if (yPix == 1) {
                y.position(r * yRow); y.get(out, o, w); o += w
            } else for (c in 0 until w) out[o++] = y.get(r * yRow + c * yPix)
        }
        // U then V
        for (i in 1..2) {
            val b = planes[i].buffer; val row = planes[i].rowStride; val pix = planes[i].pixelStride
            for (r in 0 until ch) {
                val base = r * row
                if (pix == 1) {
                    b.position(base); b.get(out, o, cw); o += cw
                } else for (c in 0 until cw) out[o++] = b.get(base + c * pix)
            }
        }
        return out
    }

    companion object { private const val TAG = "TgCallManager" }
}
