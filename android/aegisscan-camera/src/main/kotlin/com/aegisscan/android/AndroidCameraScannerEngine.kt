// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.util.Size
import androidx.annotation.MainThread
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.aegisscan.core.BarcodeItem
import com.aegisscan.core.BoundingBox
import com.aegisscan.core.LuhnValidator
import com.aegisscan.core.SecurePan
import com.aegisscan.core.SpatialMatcher
import com.aegisscan.core.SpatialMatcherOptions
import com.aegisscan.core.TextItem
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX + ML Kit scanner for payment cards and voucher barcodes.
 *
 * ## Back-pressure (two layers)
 * 1. `STRATEGY_KEEP_ONLY_LATEST`: CameraX holds at most one pending frame and
 *    drops older ones on its own.
 * 2. [isProcessingFrame] is an `AtomicBoolean` gate. If inference is still
 *    running, [analyze] closes the new frame right away (O(1), zero work).
 *
 * ## Frame lifecycle
 * `imageProxy.close()` is ALWAYS called in a `finally` block: right away on
 * drop, rejection, or error, and only after both ML Kit tasks finish
 * otherwise. ML Kit reads the `media.Image` asynchronously, so closing early
 * would hand it a recycled buffer.
 *
 * ## Lifecycle & leaks
 * Inference runs in `lifecycleOwner.lifecycleScope`, which is cancelled
 * automatically on `ON_DESTROY`. The ML Kit clients and the analysis
 * executor are closed in [shutdown], which is also wired to that scope.
 *
 * ## PCI-DSS
 * No disk caching, no `SharedPreferences`, no `Log` calls with recognized
 * text. PANs travel only as [SecurePan] (a wipeable `CharArray`) and are
 * wiped right after the listener returns.
 */
class AndroidCameraScannerEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val config: ScannerConfig = ScannerConfig(),
    private val listener: (ScanEvent) -> Unit,
) : ImageAnalysis.Analyzer {

    private val analysisExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "aegisscan-analysis").apply { isDaemon = true } }

    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient()

    /** Atomic back-pressure gate: true while a frame is in flight. */
    private val isProcessingFrame = AtomicBoolean(false)
    private val isShutdown = AtomicBoolean(false)

    private val framesReceived = AtomicLong()
    private val framesDropped = AtomicLong()
    private val framesAnalyzed = AtomicLong()
    private val framesRejected = AtomicLong()

    /**
     * Single-thread dispatcher over [analysisExecutor]. All result handling
     * (matcher scratch, card confirmation state) is confined to this thread,
     * so it needs no locks and stays race-free.
     */
    private val analysisDispatcher = analysisExecutor.asCoroutineDispatcher()

    // Confined to analysisDispatcher.
    private val matcher = SpatialMatcher(SpatialMatcherOptions())
    private var pendingCard: SecurePan? = null
    private var pendingHits = 0

    // Main-thread only.
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null

    val stats: ScannerStats
        get() = ScannerStats(framesReceived.get(), framesDropped.get(), framesAnalyzed.get(), framesRejected.get())

    init {
        // Deterministic cleanup bound to the owner's lifecycle (no leaks).
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = shutdown()
        })
    }

    // ---------------------------------------------------------------- Binding

    /**
     * Checks the CAMERA permission and binds an [ImageAnalysis] use case to the
     * lifecycle owner. Extra use cases (e.g. a `Preview`) can be bound
     * together with it. Errors are delivered through the listener.
     */
    @MainThread
    fun start(vararg extraUseCases: UseCase) {
        if (isShutdown.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            emitError(ScannerError.CameraPermissionDenied)
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    emitError(ScannerError.CameraUnavailable)
                    return@addListener
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(config.targetWidth, config.targetHeight),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, this) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis, *extraUseCases,
                )
                cameraProvider = provider
                analysisUseCase = analysis
            } catch (e: Exception) {
                emitError(ScannerError.BindFailed(e.javaClass.simpleName))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Unbinds the analysis use case and wipes any unconfirmed card candidate. */
    @MainThread
    fun stop() {
        analysisUseCase?.let { use ->
            use.clearAnalyzer()
            cameraProvider?.unbind(use)
        }
        analysisUseCase = null
        lifecycleOwner.lifecycleScope.launch(analysisDispatcher) { resetCandidate() }
    }

    /** Releases ML Kit clients and the executor. Idempotent; runs on ON_DESTROY. */
    fun shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return
        analysisUseCase?.clearAnalyzer()
        analysisExecutor.execute {
            resetCandidate()
            textRecognizer.close()
            barcodeScanner.close()
        }
        analysisExecutor.shutdown()
    }

    // --------------------------------------------------------------- Analyzer

    /**
     * Called by CameraX on [analysisExecutor]. Never blocks: when busy, the
     * frame is closed and dropped right away.
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        framesReceived.incrementAndGet()
        var handedOff = false
        try {
            if (isShutdown.get()) return
            if (!isProcessingFrame.compareAndSet(false, true)) {
                framesDropped.incrementAndGet()  // Busy: drop instantly.
                return
            }
            val rejection = validate(imageProxy)
            val mediaImage = imageProxy.image
            if (rejection != null || mediaImage == null) {
                framesRejected.incrementAndGet()
                isProcessingFrame.set(false)
                if (config.reportFrameRejections) {
                    emitError(ScannerError.FrameRejected(rejection ?: FrameRejection.NULL_IMAGE))
                }
                return
            }
            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            // ATOMIC: the body (and so its finally) runs even if the lifecycle
            // scope was cancelled meanwhile, so the frame is always closed.
            lifecycleOwner.lifecycleScope.launch(analysisDispatcher, CoroutineStart.ATOMIC) {
                try {
                    runInference(input)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emitError(ScannerError.InferenceFailed(e.javaClass.simpleName))
                } finally {
                    imageProxy.close()  // Only after both ML Kit tasks finished.
                    isProcessingFrame.set(false)
                }
            }
            handedOff = true
        } catch (e: Exception) {
            isProcessingFrame.set(false)
            emitError(ScannerError.InferenceFailed(e.javaClass.simpleName))
        } finally {
            if (!handedOff) imageProxy.close()  // Drop / reject / error path.
        }
    }

    private fun validate(imageProxy: ImageProxy): FrameRejection? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return FrameRejection.UNSUPPORTED_FORMAT
        val planes = imageProxy.planes
        val luma = planes.firstOrNull()?.let {
            FrameGuard.LumaPlane(
                imageProxy.width, imageProxy.height, it.rowStride, it.pixelStride,
                it.buffer.capacity(),
            )
        }
        return FrameGuard.validate(luma, planes.size, config.minAspectRatio, config.maxAspectRatio)
    }

    /** Runs text recognition and barcode scanning concurrently on one frame. */
    private suspend fun runInference(input: InputImage) = coroutineScope {
        val textJob = async {
            if (config.detectCards || config.detectVouchers) textRecognizer.process(input).await()
            else null
        }
        val barcodeJob = async {
            if (config.detectVouchers) barcodeScanner.process(input).await() else emptyList()
        }
        val text = textJob.await()
        val barcodes = barcodeJob.await()
        framesAnalyzed.incrementAndGet()
        handleResults(text, barcodes)
    }

    // ---------------------------------------------------------- Core logic

    private fun handleResults(text: Text?, barcodes: List<Barcode>) {
        val lines = ArrayList<TextItem>()
        text?.textBlocks?.forEach { block ->
            block.lines.forEach { line ->
                val box = line.boundingBox ?: return@forEach
                lines += TextItem(line.text, box.toCore())
            }
        }
        if (config.detectCards) processCard(lines)
        if (config.detectVouchers && barcodes.isNotEmpty() && lines.isNotEmpty()) {
            val items = barcodes.mapNotNull { b ->
                val payload = b.rawValue ?: return@mapNotNull null
                val box = b.boundingBox ?: return@mapNotNull null
                if (payload.isEmpty()) null else BarcodeItem(payload, box.toCore(), b.format)
            }
            val matches = matcher.matchBarcodesWithText(items, lines)
            if (matches.isNotEmpty()) emit(ScanEvent.VouchersMatched(matches))
        }
    }

    /**
     * Lines are joined with '\n', which the validator treats as a hard break,
     * so digits from different lines are never glued into one PAN.
     */
    private fun processCard(lines: List<TextItem>) {
        val joined = StringBuilder()
        lines.forEachIndexed { i, l ->
            if (i > 0) joined.append('\n')
            joined.append(l.text)
        }
        val pan = LuhnValidator.extractCardDetails(joined)
        joined.setLength(0)
        if (pan == null) {
            resetCandidate()
            return
        }
        val pending = pendingCard
        if (pending != null && pending.contentEquals(pan)) {
            pan.close()
            pendingHits++
        } else {
            pending?.close()
            pendingCard = pan
            pendingHits = 1
        }
        if (pendingHits >= maxOf(1, config.requiredConsecutiveCardHits)) {
            val confirmed = pendingCard ?: return
            pendingCard = null
            pendingHits = 0
            deliverCard(confirmed)
        }
    }

    private fun resetCandidate() {
        pendingCard?.close()
        pendingCard = null
        pendingHits = 0
    }

    // -------------------------------------------------------------- Delivery

    private fun deliverCard(pan: SecurePan) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main, CoroutineStart.ATOMIC) {
            pan.use { listener(ScanEvent.CardDetected(it)) }  // Wiped on return.
        }
    }

    private fun emit(event: ScanEvent) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) { listener(event) }
    }

    private fun emitError(error: ScannerError) = emit(ScanEvent.Error(error))

    private fun Rect.toCore() =
        BoundingBox(left.toFloat(), top.toFloat(), width().toFloat(), height().toFloat())
}

/** Bridges a Play Services [Task] to coroutines without blocking a thread. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener(Runnable::run) { task ->
        val error = task.exception
        when {
            task.isCanceled -> cont.cancel()
            error != null -> cont.resumeWithException(error)
            else -> cont.resume(task.result)
        }
    }
}
