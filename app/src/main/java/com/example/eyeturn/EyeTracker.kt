package com.example.eyeturn

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker.HandLandmarkerOptions
import kotlin.math.hypot

/** 眨眼翻頁：閉眼達標再睜眼=下頁，同段有拳頭=上頁。Camera2 前鏡頭 + Face blendshape + Hand 幾何。 */
class EyeTracker(
    private val ctx: Context,
    private val onNext: () -> Boolean,
    private val onPrev: () -> Boolean,
    private val onStatus: (String) -> Unit = {},
    private val onLines: (String, String, String) -> Unit = { _, _, _ -> },
) {
    companion object {
        const val MODEL_ASSET = "face_landmarker.task"
        const val HAND_MODEL_ASSET = "hand_landmarker.task"
        const val W = 480
        const val H = 360
        private const val FRAME_GAP_MS = 100L // ~10fps，省電且夠用
        private const val NEED_CLOSED = 6 // demo 預設；實跑用 needClosed（設定頁可調）
        // ponytail: 只認拳頭，其餘手勢當沒看到；鏡像不影響伸/屈判斷
        fun isFist(lm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Boolean {
            fun dist(a: Int, b: Int) = hypot((lm[a].x() - lm[b].x()).toDouble(), (lm[a].y() - lm[b].y()).toDouble())
            fun extended(tip: Int, pip: Int) = dist(tip, 0) > dist(pip, 0) * 1.15
            // ponytail: 手背透視壓扁時腕比失效，加局部伸直當 OR，正反面皆通
            fun straight(tip: Int, pip: Int, mcp: Int) = dist(tip, mcp) > dist(pip, mcp) * 1.6
            val thumbWide = dist(4, 17) > dist(2, 17) * 1.2
            val idx = extended(8, 6) || straight(8, 6, 5)
            val mid = extended(12, 10) || straight(12, 10, 9)
            val ring = extended(16, 14) || straight(16, 14, 13)
            val pinky = extended(20, 18) || straight(20, 18, 17)
            // ponytail: 半握也算拳（伸開≤1指），拇指維持嚴格防誤觸
            return !thumbWide && listOf(idx, mid, ring, pinky).count { it } <= 1
        }

        /** 眨眼邊緣邏輯自檢（無需相機/模型）：回傳每次觸發的幀 index。 */
        fun blinkFiresAt(closed: List<Boolean>, need: Int = 6): List<Int> {
            val fires = mutableListOf<Int>()
            var streak = 0
            var armed = true
            closed.forEachIndexed { i, c ->
                if (!c) { streak = 0; armed = true; return@forEachIndexed }
                if (!armed) return@forEachIndexed
                streak++
                if (streak >= need) { armed = false; streak = 0; fires.add(i) }
            }
            return fires
        }

        fun frontSensorDeg(ctx: Context): Int {
            return try {
                val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = mgr.cameraIdList.firstOrNull {
                    mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                } ?: return 0
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            } catch (_: Exception) { 0 }
        }

        fun displayDeg(ctx: Context): Int {
            return try {
                val rot = if (android.os.Build.VERSION.SDK_INT >= 30) ctx.display?.rotation ?: 0
                else @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
                when (rot) {
                    Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270; else -> 0
                }
            } catch (_: Exception) { 0 }
        }

        /**
         * 前鏡頭預覽塞滿 TextureView：轉正＋鏡像，橫豎皆通（buffer 固定 W×H，呼叫前先
         * surfaceTexture.setDefaultBufferSize(W, H)；view 量好尺寸後呼叫，resize/轉屏重調）。
         */
        fun fitPreview(tv: android.view.TextureView, sensorDeg: Int, dispDeg: Int) {
            val vw = tv.width; val vh = tv.height
            if (vw == 0 || vh == 0) return
            val swap = dispDeg == 90 || dispDeg == 270
            val bw = if (swap) H else W
            val bh = if (swap) W else H
            val viewRect = android.graphics.RectF(0f, 0f, vw.toFloat(), vh.toFloat())
            val bufRect = android.graphics.RectF(0f, 0f, bw.toFloat(), bh.toFloat())
            bufRect.offset(viewRect.centerX() - bufRect.centerX(), viewRect.centerY() - bufRect.centerY())
            val m = android.graphics.Matrix()
            m.setRectToRect(viewRect, bufRect, android.graphics.Matrix.ScaleToFit.FILL)
            val scale = maxOf(vh.toFloat() / bh, vw.toFloat() / bw)
            m.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
            // ponytail: 顯示帶鏡像時旋轉取反才正——豎屏 0 不變，橫屏 90↔270 對調（180 自反不變）；
            // sensor 在此機上掉出公式，參數保留以備他機
            val total = (360 - dispDeg) % 360
            // ponytail: 鏡像靠 HAL 自帶，不進矩陣——矩陣裡鏡像會跟旋轉打架；這裡只轉正＋填滿
            m.postRotate(total.toFloat(), viewRect.centerX(), viewRect.centerY())
            tv.setTransform(m)
        }

        fun demo(): String {
            // 自然眨眼（2幀閉）不觸發
            check(blinkFiresAt(listOf(false, false, true, true, false, false)).isEmpty()) { "自然眨眼不應觸發" }
            // 閉眼維持 6 幀在第 5 個 index 觸發（0-based）
            check(blinkFiresAt(List(6) { true }) == listOf(5)) { "閉眼6幀應觸發" }
            // 觸發後閉著不連翻，睜眼後再閉才翻
            check(blinkFiresAt(List(12) { true }) == listOf(5)) { "同一次閉眼只翻一次" }
            check(blinkFiresAt(List(6) { true } + listOf(false) + List(6) { true }) == listOf(5, 12)) { "睜眼後再閉應再翻" }
            // 拳：全握＋半握（伸開≤1指）算拳，兩指以上不算
            check(isFist(fakeHand(emptySet()))) { "全握應算拳" }
            check(isFist(fakeHand(setOf(0)))) { "半握應算拳" }
            check(!isFist(fakeHand(setOf(0, 1)))) { "兩指伸不應算拳" }
            check(!isFist(fakeHand(setOf(0, 1, 2, 3)))) { "全張不應算拳" }
            return "EyeTracker demo OK"
        }

        // ponytail: 合成手驗 isFist，不需相機/模型；0=食..3=尾，open 指伸開的手指
        fun fakeHand(open: Set<Int>): List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> {
            val mcpX = listOf(0.38f, 0.46f, 0.54f, 0.62f)
            val pts = Array(21) { floatArrayOf(0.5f, 0.5f) }
            pts[0] = floatArrayOf(0.5f, 0.9f)
            pts[1] = floatArrayOf(0.42f, 0.8f); pts[2] = floatArrayOf(0.38f, 0.75f)
            pts[3] = floatArrayOf(0.36f, 0.7f); pts[4] = floatArrayOf(0.4f, 0.66f)
            for (f in 0..3) {
                val x = mcpX[f]
                val b = 5 + f * 4
                if (f in open) {
                    pts[b] = floatArrayOf(x, 0.7f); pts[b + 1] = floatArrayOf(x, 0.55f)
                    pts[b + 2] = floatArrayOf(x, 0.4f); pts[b + 3] = floatArrayOf(x, 0.28f)
                } else {
                    pts[b] = floatArrayOf(x, 0.7f); pts[b + 1] = floatArrayOf(x, 0.64f)
                    pts[b + 2] = floatArrayOf(x, 0.61f); pts[b + 3] = floatArrayOf(x + 0.005f, 0.62f)
                }
            }
            return pts.map { com.google.mediapipe.tasks.components.containers.NormalizedLandmark.create(it[0], it[1], 0f) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var reader: ImageReader? = null
    private var cam: android.hardware.camera2.CameraDevice? = null
    private var session: android.hardware.camera2.CameraCaptureSession? = null
    private var face: FaceLandmarker? = null
    private var hand: HandLandmarker? = null
    private var lastFrameAt = 0L
    private var busy = false
    private var closedStreak = 0
    private var openStreak = 0 // 連 2 幀睜眼才算睜，單幀抖動不清零
    private var faceLostStreak = 0 // 跟丟 2 幀內凍住，不清零（閉用力頭晃也會抖）
    @Volatile private var lastFiredDir = 0 // 0=沒翻，1=下頁，2=上頁；顯示後清零
    @Volatile private var pendingDir = 0 // 閉眼達標掛起的方向；睜眼那一下才翻
    private var armed = true // 睜眼才重上膛；觸發後閉著不連翻
    private var lastFireAt = 0L
    private var lastStatusAt = 0L
    @Volatile var cooldownMs = 1500L
    @Volatile var needClosed = 6 // 閉眼幾幀才翻（~10fps，6≈0.6秒）；設定頁可調 3~8
    @Volatile var blinkScore = 0.5f // eyeBlink 超過算閉；設定頁可調 0.3~0.7（越小越靈）
    @Volatile var running = false

    /** model 缺失/權限不足回 false，呼叫方 Toast 提示，不炸。preview 有給才顯示小窗。 */
    fun start(preview: Surface? = null): Boolean {
        if (running) return true
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("缺相機權限"); return false
        }
        try {
            if (face == null) {
                val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build()
                val opt = FaceLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1).setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f).setMinTrackingConfidence(0.5f)
                    .setOutputFaceBlendshapes(true).build()
                face = FaceLandmarker.createFromOptions(ctx, opt)
            }
        } catch (e: Exception) {
            status("缺模型：assets/$MODEL_ASSET 沒放"); return false
        }
        try {
            if (hand == null) {
                val base = BaseOptions.builder().setModelAssetPath(HAND_MODEL_ASSET).setDelegate(Delegate.CPU).build()
                val opt = HandLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(RunningMode.IMAGE)
                    .setNumHands(1).setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f).setMinTrackingConfidence(0.5f).build()
                hand = HandLandmarker.createFromOptions(ctx, opt)
            }
        } catch (e: Exception) {
            status("缺拳頭模型：只剩眨眼下頁")
            hand = null // ponytail: 手模型缺了照跑，眨眼=下頁不受影響
        }
        try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val front = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: run { status("找不到前鏡頭"); return false }
            val rot = sensorToDisplay(mgr, front)
            thread = HandlerThread("eye").also { it.start() }
            bg = Handler(thread!!.looper)
            reader = ImageReader.newInstance(W, H, android.graphics.ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ r -> onFrame(r.acquireLatestImage(), rot) }, bg)
            }
            @Suppress("MissingPermission")
            mgr.openCamera(front, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(d: android.hardware.camera2.CameraDevice) {
                    cam = d
                    try {
                        val req = d.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                        // ponytail: 有預覽才加第二路；辨識走 ImageReader 不受影響
                        val targets = mutableListOf(reader!!.surface)
                        preview?.let { targets.add(it) }
                        targets.forEach { req.addTarget(it) }
                        d.createCaptureSession(targets, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                                session = s
                                req.set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO)
                                s.setRepeatingRequest(req.build(), null, bg)
                            }
                            override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) { status("相機啟動失敗") }
                        }, bg)
                    } catch (e: Exception) { status("相機啟動失敗") }
                }
                override fun onDisconnected(d: android.hardware.camera2.CameraDevice) { stop() }
                override fun onError(d: android.hardware.camera2.CameraDevice, e: Int) { status("相機錯誤 $e"); stop() }
            }, bg)
            running = true
            return true
        } catch (e: SecurityException) {
            status("缺相機權限"); stop(); return false
        } catch (e: Exception) {
            status("相機開不了：${e.message}"); stop(); return false
        }
    }

    fun stop() {
        running = false
        try { session?.stopRepeating(); session?.close() } catch (_: Exception) {}
        try { cam?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        session = null; cam = null; reader = null; thread = null; bg = null
        busy = false; closedStreak = 0; openStreak = 0; faceLostStreak = 0; armed = true; lastFiredDir = 0; pendingDir = 0
    }

    fun close() {
        stop()
        try { face?.close() } catch (_: Exception) {}; face = null
        try { hand?.close() } catch (_: Exception) {}; hand = null
    }

    private fun onFrame(img: android.media.Image?, rot: Int) {
        if (img == null) return
        try {
            val now = android.os.SystemClock.uptimeMillis()
            if (!running || busy || now - lastFrameAt < FRAME_GAP_MS) return
            lastFrameAt = now
            busy = true
            val bmp = yuvToBitmap(img, rot)
            bg?.post { detect(bmp) }
        } finally { try { img.close() } catch (_: Exception) {} }
    }

    private fun detect(bmp: Bitmap) {
        try {
            val fm = face ?: return
            val mpImg = BitmapImageBuilder(bmp).build()
            val fres = fm.detect(mpImg)
            val faces = fres.faceLandmarks()
            if (faces.isEmpty()) {
                if (++faceLostStreak <= 5) return // 瞇眼會短暫跟丟：凍住，不計不清，不刷提示
                closedStreak = 0; openStreak = 0
                statusThrottled("臉出鏡")
                lines("出鏡", "睜眼", "X")
                return
            }
            faceLostStreak = 0
            var blink = 0f
            try {
                // ponytail: 這版 faceBlendshapes() 包 Optional，先 isPresent 再 get
                val blends = fres.faceBlendshapes()
                if (blends.isPresent) for (c in blends.get()[0]) {
                    if (c.categoryName() == "eyeBlinkLeft" || c.categoryName() == "eyeBlinkRight") {
                        if (c.score() > blink) blink = c.score()
                    }
                }
            } catch (_: Exception) {}
            // ponytail: 抖動帶跟門檻等比（50% 時=0.1 跟舊版一致）；固定 0.1 在 10% 門檻下會永遠不清零不上膛
            val hys = (blinkScore * 0.3f).coerceAtMost(0.1f)
            if (blink < blinkScore - hys) {
                // 睜眼：連 2 幀才算數，單幀抖動不清零；達標掛起的在睜眼這一下才翻
                val dir = pendingDir
                val wasClosed = closedStreak > 0 || !armed || dir != 0
                if (++openStreak >= 2) { closedStreak = 0; armed = true }
                if (dir != 0) {
                    pendingDir = 0
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFireAt < cooldownMs) {
                        status(if (dir == 2) "開眼上" else "開眼下") // 冷卻吞掉：只顯示過渡
                    } else {
                        lastFireAt = now
                        status(if (dir == 2) "開眼上" else "開眼下")
                        main.post { lastFiredDir = if (dir == 2) { if (onPrev()) 2 else 0 } else { if (onNext()) 1 else 0 } }
                    }
                } else if (lastFiredDir != 0) {
                    status(if (lastFiredDir == 2) "上一頁" else "下一頁")
                    lastFiredDir = 0
                } else statusThrottled("")
                val fistOpen = isFistNow(mpImg) // 拳頭先舉著待命也看得到
                lines("入鏡", "睜眼", if (fistOpen) "拳" else "X")
                return
            }
            openStreak = 0
            if (!armed) { // 觸發後還閉著：不連翻
                statusThrottled("閉眼")
                lines("入鏡", "閉眼", lastFist)
                return
            }
            if (blink < blinkScore) {
                // 抖動帶：凍住，既不計也不清
                statusThrottled(if (closedStreak > 0) "閉眼" else "")
                if (closedStreak > 0) lines("入鏡", "閉眼", lastFist)
                else { val fj = isFistNow(mpImg); lines("入鏡", "睜眼", if (fj) "拳" else "X") }
                return
            }
            closedStreak++
            val fist = isFistNow(mpImg)
            lines("入鏡", "閉眼", if (fist) "拳" else "X")
            statusThrottled("閉眼")
            if (closedStreak >= needClosed) {
                // 達標先掛起，不在這裡翻；等睜眼那一下才翻（閉->開模型，提示和動作永遠對得上）
                closedStreak = 0; armed = false
                pendingDir = if (fist) 2 else 1
            }
        } catch (_: Exception) {
        } finally { busy = false }
    }

    // ponytail: 同幀現算拳頭，比跨幀狀態同步簡單且一定對齊眨眼時刻
    private fun isFistNow(mpImg: com.google.mediapipe.framework.image.MPImage): Boolean {
        return try {
            val hands = (hand ?: return false).detect(mpImg).landmarks()
            hands.isNotEmpty() && isFist(hands[0])
        } catch (_: Exception) { false }
    }

    private fun status(s: String) { main.post { try { onStatus(s) } catch (_: Exception) {} } }

    private var lastFist = "X" // 最新一幀拳頭狀態；閉著不動時沿用，不重跑手模型
    private fun lines(face: String, eye: String, fist: String) {
        lastFist = fist
        main.post { try { onLines(face, eye, fist) } catch (_: Exception) {} }
    }

    private fun statusThrottled(s: String) {
        if (s.isEmpty()) return // ponytail: 提示 sticky，顯示後不自動清掉
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStatusAt < 800) return
        lastStatusAt = now; status(s)
    }

    private fun sensorToDisplay(mgr: CameraManager, id: String): Int {
        return try {
            val sensor = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val disp = if (android.os.Build.VERSION.SDK_INT >= 30) ctx.display?.rotation ?: 0
            else @Suppress("DEPRECATION") wm.defaultDisplay.rotation
            val deg = when (disp) {
                Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270 else -> 0
            }
            (sensor - deg + 360) % 360 // 前鏡頭直立通常 270
        } catch (_: Exception) { 0 }
    }

    // ponytail: 480x360 餵模型；逐像素轉 10fps 無壓力
    private fun yuvToBitmap(img: android.media.Image, rot: Int): Bitmap {
        val y = img.planes[0].buffer; val u = img.planes[1].buffer; val v = img.planes[2].buffer
        val ys = img.planes[0].rowStride; val us = img.planes[1].rowStride; val ups = img.planes[1].pixelStride
        val w = img.width; val h = img.height
        val out = IntArray(w * h)
        var i = 0
        for (r in 0 until h) for (c in 0 until w) {
            val yy = (y.get(r * ys + c).toInt() and 0xFF) - 16
            val ui = (u.get((r / 2) * us + (c / 2) * ups).toInt() and 0xFF) - 128
            val vi = (v.get((r / 2) * us + (c / 2) * ups).toInt() and 0xFF) - 128
            var rr = (1.164f * yy + 1.596f * vi).toInt()
            var gg = (1.164f * yy - 0.391f * ui - 0.813f * vi).toInt()
            var bb = (1.164f * yy + 2.018f * ui).toInt()
            rr = rr.coerceIn(0, 255); gg = gg.coerceIn(0, 255); bb = bb.coerceIn(0, 255)
            out[i++] = 0xFF000000.toInt() or (rr shl 16) or (gg shl 8) or bb
        }
        var bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, w, h, m, false)
        }
        return if (bmp.width != W || bmp.height != H) Bitmap.createScaledBitmap(bmp, W, H, false) else bmp
    }
}
