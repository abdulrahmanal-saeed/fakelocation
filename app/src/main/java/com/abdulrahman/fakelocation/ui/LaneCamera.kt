package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.nav.LaneDetector
import com.abdulrahman.fakelocation.nav.NavSession
import java.util.concurrent.Executors

/**
 * The back camera watching the road (phone in a dashboard holder): finds the lane lines a few times a second,
 * tells navigation when you cross one, and shows what it sees in a small preview.
 */
class LaneCamera(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val preview: PreviewView,
    private val overlay: LaneOverlay,
) {
    private val detector = LaneDetector()
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastFrameAt = 0L
    private var running = false
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)

    fun start() {
        if (running) return
        running = true
        owner.lifecycleScope.launch {
            val cameras = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull() ?: return@launch
            if (!running) return@launch
            provider = cameras
            val selector = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val show = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            runCatching {
                cameras.unbindAll()
                cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, show, analysis)
            }
        }
    }

    fun stop() {
        running = false
        provider?.unbindAll()
        provider = null
        overlay.show(null)
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < 120) return // ~8 frames a second is plenty
            lastFrameAt = now
            val planes = image.planes
            y = copy(planes[0].buffer, y)
            u = copy(planes[1].buffer, u)
            v = copy(planes[2].buffer, v)
            val frame = LaneDetector.Frame(
                image.width, image.height, image.imageInfo.rotationDegrees,
                y, planes[0].rowStride, u, v, planes[1].rowStride, planes[1].pixelStride,
            )
            val seen = detector.detect(frame)
            val event = detector.track(seen, now)
            overlay.post {
                overlay.show(seen)
                event?.let(NavSession::onCameraEvent)
            }
        }
    }

    private fun copy(buffer: java.nio.ByteBuffer, into: ByteArray): ByteArray {
        buffer.rewind()
        val out = if (into.size == buffer.remaining()) into else ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}

/** Draws the lane lines the camera found over its preview (green; yellow for the yellow edge line). */
class LaneOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var seen: LaneDetector.Observation? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    fun show(observation: LaneDetector.Observation?) {
        seen = observation
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // The preview fits a 3:4 (portrait) picture into this view.
        val aspect = 3f / 4f
        val w: Float
        val h: Float
        if (width / height.toFloat() > aspect) {
            h = height.toFloat(); w = h * aspect
        } else {
            w = width.toFloat(); h = w / aspect
        }
        val left = (width - w) / 2
        val top = (height - h) / 2
        val o = seen ?: return
        fun line(l: LaneDetector.Line?, color: Int) {
            l ?: return
            paint.color = color
            canvas.drawLine(left + l.xAt(l.top).toFloat() * w, top + l.top.toFloat() * h, left + l.xBottom.toFloat() * w, top + l.bottom.toFloat() * h, paint)
        }
        line(o.left, if (o.leftIsYellow) ContextCompat.getColor(context, R.color.traffic_slow) else ContextCompat.getColor(context, R.color.route_green))
        line(o.right, ContextCompat.getColor(context, R.color.route_green))
        o.position?.let {
            paint.color = ContextCompat.getColor(context, R.color.nav_blue)
            val x = left + w / 2
            canvas.drawLine(x, top + h * 0.8f, x, top + h * 0.95f, paint)
        }
    }
}
