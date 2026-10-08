package com.example.eyeturn

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast

/** 眨眼翻頁：閉眼維持約0.6秒=下頁，同幀有拳頭=上頁。前鏡頭只在閱讀時開，其餘門控沿用手勢版。 */
class PageTurnService : AccessibilityService() {

    companion object {
        const val PREFS = "cfg"
        const val KEY_COOLDOWN = "cooldown"
        const val KEY_OVERLAY = "overlay"
        const val KEY_APPS = "apps"
        const val KEY_RUNNING = "run"
        const val KEY_SERVICE = "service"
        const val KEY_ALPHA = "alpha"
        const val KEY_PVW = "pvw"
        const val KEY_PVX_P = "pvxp"
        const val KEY_PVY_P = "pvyp"
        const val KEY_PVX_L = "pvxl"
        const val KEY_PVY_L = "pvyl"
        const val KEY_PVALPHA = "pvAlpha"
        const val KEY_IDLE = "idle"
        const val KEY_NEED = "need"
        const val KEY_BLINK = "blink"
        val DEFAULT_APPS = setOf("com.tencent.weread")

        // ponytail: static 當跨 Activity/Service 通訊，存 DB / Intent 是多餘的
        @Volatile var serviceOn = false
        @Volatile var running = false
        @Volatile var cooldownMs = 1500L
        @Volatile var overlayOn = true
        @Volatile var allowedApps: Set<String> = DEFAULT_APPS
        @Volatile var ballAlpha = 50
        @Volatile var previewWdp = 200 // 懸浮預覽窗寬 dp，高=寬*3/4；default 200x150
        // ponytail: 位置直橫分開記（-1=沒擺過，用預設左上）；大小共用 previewWdp
        @Volatile var pvXP = -1; @Volatile var pvYP = -1
        @Volatile var pvXL = -1; @Volatile var pvYL = -1
        @Volatile var previewAlpha = 100
        @Volatile var idleMs = 5 * 60 * 1000L // 閒置多久沒翻頁自動停；default 5 分鐘
        @Volatile var needClosed = 4 // 閉眼幾幀才翻；default 4（~0.4秒，比 6 靈）
        @Volatile var blinkScore = 50 // eyeBlink 百分比，超過算閉；越小越靈
        @Volatile var currentPkg: String = ""
        @Volatile var instance: PageTurnService? = null

        fun loadPrefs(ctx: Context) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            serviceOn = p.getBoolean(KEY_SERVICE, false)
            running = p.getBoolean(KEY_RUNNING, false)
            cooldownMs = p.getInt(KEY_COOLDOWN, 3000).coerceIn(1000, 30000).toLong()
            overlayOn = p.getBoolean(KEY_OVERLAY, true)
            allowedApps = p.getStringSet(KEY_APPS, DEFAULT_APPS) ?: DEFAULT_APPS
            ballAlpha = p.getInt(KEY_ALPHA, 50).coerceIn(10, 100)
            previewWdp = p.getInt(KEY_PVW, 200).coerceIn(80, 400)
            pvXP = p.getInt(KEY_PVX_P, -1); pvYP = p.getInt(KEY_PVY_P, -1)
            pvXL = p.getInt(KEY_PVX_L, -1); pvYL = p.getInt(KEY_PVY_L, -1)
            previewAlpha = p.getInt(KEY_PVALPHA, 100).coerceIn(10, 100)
            idleMs = p.getInt(KEY_IDLE, 5).coerceIn(1, 30) * 60 * 1000L
            needClosed = p.getInt(KEY_NEED, 4).coerceIn(3, 8)
            blinkScore = p.getInt(KEY_BLINK, 50).coerceIn(30, 70)
        }

        fun saveRunning(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RUNNING, running)
                .putBoolean(KEY_SERVICE, serviceOn).apply()
        }
    }

    private var lastPkgAt = 0L
    @Volatile var inOwnApp = false
    private var tracker: EyeTracker? = null
    private var trackerHasPreview = false
    private var lastFlipAt = 0L // 上次成功翻頁；閒置超時自動停用
    private var coolStartAt = 0L // 綠條計時起點；0=就緒隱藏，只在真翻頁後跑一次
    private val handler = Handler(Looper.getMainLooper())

    // ponytail: 瘦身時砍掉 tick 迴圈是回歸——事件一漏球就回不來；2 秒看門狗只做狀態自癒，不跑相機不耗電
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                refreshForeground() // 用活的 active window 校準，事件漏了也不瞎
                if (overlayOn && ball == null && surelyInside()) showOverlay()
                // ponytail: 相機跑著（翻頁中）不藏球——不然停止鍵沒了；真在外面事件會先停相機，下輪再藏
                else if (ball != null && tracker?.running != true && !surelyInside()) hideOverlay()
                refreshBall()
                // 閒置超時：一段時間沒翻頁就關相機停球（省電，人走開不用管）
                if (active() && SystemClock.uptimeMillis() - lastFlipAt > idleMs) {
                    stop()
                    Toast.makeText(this@PageTurnService, "${idleMs / 60000} 分鐘沒翻頁，已自動停止", Toast.LENGTH_SHORT).show()
                } else if (tracker?.running == true) {
                    if (previewBox == null) showPreviewWindow()
                    else if (previewSurface != null && !trackerHasPreview) {
                        tracker?.stop() // 裸跑（沒接預覽面）→ 重啟接入
                        startTracker(previewSurface)
                    }
                }
            } catch (_: Exception) {
            } finally {
                handler.postDelayed(this, 2000)
            }
        }
    }

    private fun active() = serviceOn && running

    private fun visiblePkg(): String? {
        val v = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if (v.isNullOrEmpty() || transientPkgs.contains(v)) return null
        return v
    }

    private fun refreshForeground() {
        visiblePkg()?.let {
            if (it != currentPkg) {
                currentPkg = it
                lastPkgAt = SystemClock.uptimeMillis()
            }
            inOwnApp = it == packageName
        }
    }

    /** 真的在外面才回 true。自家設定頁不算外面（翻頁由 inOwnApp 擋，球要留）。 */
    private fun surelyOutside(): Boolean {
        visiblePkg()?.let {
            if (it == packageName) return false
            return !isAllowed(it)
        }
        if (currentPkg.isEmpty() || isAllowed(currentPkg)) return false
        return SystemClock.uptimeMillis() - lastPkgAt < 3000
    }

    /**
     * 真的在裡面（自家或白名單）才回 true，預設藏球。
     * ponytail: 10 秒內事件優先——進書頁事件可靠，眼睛在特殊 ROM/懸浮層下會看錯對象；
     * 陳舊分支不過期（安全視窗閱讀頁無事件又看不見 root，過期會藏球）。
     */
    private fun surelyInside(): Boolean {
        if (currentPkg.isNotEmpty() && SystemClock.uptimeMillis() - lastPkgAt < 10000) {
            return if (currentPkg == packageName) inOwnApp else isAllowed(currentPkg)
        }
        visiblePkg()?.let { return it == packageName || isAllowed(it) }
        if (currentPkg.isEmpty()) return false
        if (currentPkg == packageName) return inOwnApp
        return isAllowed(currentPkg)
    }

    private fun flipBlocked(): Boolean {
        if (inOwnApp) return true
        return surelyOutside()
    }

    // ---- overlay ----
    private var wm: WindowManager? = null
    private var ball: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    // 懸浮預覽窗：跟相機同開同關；本體拖移，右下角熱區拖縮放（4:3）
    private var previewBox: View? = null
    private var previewParams: WindowManager.LayoutParams? = null
    private var previewSurface: android.view.Surface? = null
    private var previewOverlay: HandOverlay? = null
    private var coolBar: View? = null // 冷卻條：直屏貼底橫長，橫屏貼左豎長；滿=可翻，藏起=冷卻結束
    // ponytail: 100ms 一格夠看，不另起線程；預覽窗關了就停
    private val coolTick = object : Runnable {
        override fun run() {
            try {
                val b = coolBar ?: return
                val land = isLandscape()
                if (b.tag != land) refreshCoolBarGeom()
                if (coolStartAt == 0L) {
                    if (b.visibility != View.GONE) b.visibility = View.GONE
                    return
                }
                val frac = ((SystemClock.uptimeMillis() - coolStartAt).toFloat() / cooldownMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                if (frac >= 1f) b.visibility = View.GONE
                else {
                    b.visibility = View.VISIBLE
                    // ponytail: 滿條倒數——翻頁瞬間滿格，冷卻走完剛好縮光消失
                    if (land) b.scaleY = 1f - frac else b.scaleX = 1f - frac
                }
            } catch (_: Exception) {
            } finally {
                if (previewBox != null) handler.postDelayed(this, 100)
            }
        }
    }
    private var previewPending = false

    // ponytail: 蓋在 TextureView 上的透明層，只畫目標手勢的圈；不吃觸控（小窗照樣拖）
    private inner class HandOverlay(ctx: Context) : View(ctx) {
        var hg = 0; var hx = 0.5f; var hy = 0.5f
        var tipText = "" // ponytail: 診斷小字，讀書姿勢出問題時一眼分出臉出鏡/計數中/冷卻
        var rotDeg = 0 // 偵測圖轉了幾度（跟 EyeTracker.sensorToDisplay 同源）
        var totalDeg = 0 // 預覽轉了幾度（跟 fitPreview 的 total 同源）
        private val red = Paint().apply {
            color = Color.RED; style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat(); isAntiAlias = true
        }
        private val yellow = Paint().apply {
            color = Color.YELLOW; style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat(); isAntiAlias = true
        }
        private val green = Paint().apply {
            color = Color.GREEN; style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat(); isAntiAlias = true
        }
        private val txt = Paint().apply {
            color = Color.WHITE; textSize = dp(12).toFloat(); isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        fun setHand(g: Int, cx: Float, cy: Float) { hg = g; hx = cx; hy = cy; invalidate() }
        fun setTip(s: String) { tipText = s; invalidate() }
        // ponytail: 偵測圖與預覽轉向不同（差 sensor 一整圈），座標要轉回同一系再套 HAL 鏡像；
        // 轉幾度全從 sensor/display 現算，不寫死，橫豎通用
        private fun rot(x: Float, y: Float, deg: Int): Pair<Float, Float> = when (((deg % 360) + 360) % 360) {
            90 -> Pair(1 - y, x); 180 -> Pair(1 - x, 1 - y); 270 -> Pair(y, 1 - x); else -> Pair(x, y)
        }
        override fun onDraw(c: android.graphics.Canvas) {
            super.onDraw(c)
            if (hg == 1 || hg == 2 || hg == 3) {
                val ring = when (hg) { 1 -> red; 2 -> yellow; else -> green } // 眨眼紅下頁，眨眼+拳頭黃上頁，閉眼計數中綠
                // ponytail: realme GT Neo2 實測對角反——偵測座標系差半圈，補 180；豎橫同式（兩路同 track disp）
                val (xs, ys) = rot(hx, hy, 540 - rotDeg)
                val (xu, yu) = rot(xs, ys, totalDeg)
                val vx = (1 - xu) * width // HAL 自帶鏡像（豎屏已驗），App 不再翻
                val vy = yu * height
                val r = (minOf(width, height) * 0.22f).coerceAtLeast(dp(24).toFloat())
                c.drawCircle(vx.coerceIn(r, (width - r).coerceAtLeast(r)), vy.coerceIn(r, (height - r).coerceAtLeast(r)), r, ring)
            }
            if (tipText.isNotEmpty()) c.drawText(tipText, width / 2f, dp(18).toFloat(), txt)
        }
        init { isClickable = false; isFocusable = false }
    }

    override fun onServiceConnected() {
        instance = this
        loadPrefs(this)
        currentPkg = "unknown"
        lastPkgAt = SystemClock.uptimeMillis()
        refreshForeground()
        if (overlayOn && surelyInside()) showOverlay()
        if (serviceOn && running) updateCamera()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: ""
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString() ?: ""
            // ponytail: 球自身懸浮窗也會送自家包名事件（非 MainActivity），忽略否則相機開關抖動
            if (pkg == packageName && !cls.contains("MainActivity")) return
            if (pkg == packageName) {
                inOwnApp = true
                updateCamera() // 自家頁關相機（隱私+省電），running 保留
            } else if (pkg.isNotEmpty() && !transientPkgs.contains(pkg)) {
                inOwnApp = false
            }
            if (pkg.isNotEmpty() && !transientPkgs.contains(pkg) && pkg != packageName) {
                currentPkg = pkg
                lastPkgAt = SystemClock.uptimeMillis()
            }
            if (pkg.isNotEmpty()) onForeground(pkg)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            // ponytail: 手動滑頁也算活著，閒置重數；自家事件與非白名單不算
            if (active() && pkg.isNotEmpty() && pkg != packageName && isAllowed(pkg)) {
                lastFlipAt = SystemClock.uptimeMillis()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ponytail: 點位按當下 metrics 算，直橫皆通；這裡只夾球回可視範圍
        val (sw, sh) = screenSize()
        ballParams?.let { bp ->
            bp.x = bp.x.coerceIn(0, (sw - dp(56)).coerceAtLeast(0))
            bp.y = bp.y.coerceIn(0, (sh - dp(56)).coerceAtLeast(0))
            if (ball != null) try { wm?.updateViewLayout(ball, bp) } catch (_: Exception) {}
        }
        previewParams?.let { pp ->
            val maxW = (sw / 2).coerceAtLeast(dp(80))
            pp.width = pp.width.coerceIn(dp(80), maxW)
            pp.height = pp.width * 3 / 4
            // ponytail: 轉向切到該向記住的位置；沒擺過就留在原地夾回可視範圍
            val (sx, sy) = savedPos()
            pp.x = (if (sx >= 0) sx else pp.x).coerceIn(0, (sw - pp.width).coerceAtLeast(0))
            pp.y = (if (sy >= 0) sy else pp.y).coerceIn(0, (sh - pp.height).coerceAtLeast(0))
            if (previewBox != null) try { wm?.updateViewLayout(previewBox, pp) } catch (_: Exception) {}
        }
        refitPreview()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(watchdog)
        releaseCamera()
        tracker?.close(); tracker = null
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    fun start() {
        if (!serviceOn) return
        refreshForeground()
        if (inOwnApp) return // 自家頁點球無反應（第二層，點火只許在閱讀頁）
        running = true
        saveRunning(this)
        lastFlipAt = SystemClock.uptimeMillis() // 從點火起算閒置
        updateCamera()
        refreshBall()
    }

    fun stop() {
        running = false
        saveRunning(this)
        releaseCamera()
        refreshBall()
    }

    fun applySettings() {
        loadPrefs(this)
        tracker?.cooldownMs = cooldownMs
        tracker?.needClosed = needClosed
        tracker?.blinkScore = blinkScore / 100f
        if (!overlayOn) hideOverlay()
        else showOverlay()
        refitPreview() // 大小/透明度即時生效
        updateCamera()
        refreshBall()
    }

    private fun isAllowed(pkg: String): Boolean {
        if (allowedApps.isEmpty()) return true
        if (pkg.isEmpty()) return true
        return allowedApps.contains(pkg)
    }

    private val transientPkgs = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.example.handturn", // ponytail: 兄弟 App 當系統彈窗忽略，否則對方球/窗一動就被當外面藏球停相機
        "com.example.auto_turn_page"
    )

    private fun isLauncher(pkg: String): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, 0)
            .any { it.activityInfo.packageName == pkg }
    }

    private fun onForeground(pkg: String) {
        if (transientPkgs.contains(pkg)) return
        if (pkg == packageName) {
            updateCamera() // 自家頁關相機（隱私+省電），running 保留
            if (overlayOn) { showOverlay(); refreshBall() } else hideOverlay()
            return
        }
        if (isAllowed(pkg)) {
            if (overlayOn) { showOverlay(); refreshBall() }
            updateCamera()
            return
        }
        if (isLauncher(pkg)) {
            if (active()) stop()
            hideOverlay() // 桌面一定藏球，不管有沒有在翻
        } else {
            // 非白名單：一律停＋藏球；球只許出現在自家和白名單
            if (active()) stop()
            hideOverlay()
        }
    }

    // ---- camera lifecycle：只在閱讀時開 ----
    private fun updateCamera() {
        refreshForeground()
        if (active() && !flipBlocked()) ensureCamera() else releaseCamera()
    }

    private fun ensureCamera() {
        if (tracker?.running == true) return
        showPreviewWindow() // 有懸浮窗權限才有小窗；失敗就無預覽照跑
        val ps = previewSurface
        if (previewBox != null && ps == null) {
            // 等小窗表面就緒再開相機，2 秒沒好就無預覽先開
            previewPending = true
            previewBox?.postDelayed({
                if (previewPending) {
                    previewPending = false
                    startTracker(previewSurface)
                }
            }, 2000)
            return
        }
        startTracker(ps)
    }

    private fun startTracker(ps: android.view.Surface?) {
        if (tracker?.running == true) return
        val t = tracker ?: EyeTracker(
            this,
            onNext = { if (!flipBlocked()) tapNextPage() },
            onPrev = { if (!flipBlocked()) tapPrevPage() },
            // ponytail: 三色圈蓋在臉上，比底部文字一眼看出；文字提示退回設定頁測試窗
            onHand = { g, cx, cy -> try { previewOverlay?.setHand(g, cx, cy) } catch (_: Exception) {} },
            onStatus = { s -> try { previewOverlay?.setTip(s) } catch (_: Exception) {} },
        ).also { tracker = it }
        t.cooldownMs = cooldownMs
        t.needClosed = needClosed
        t.blinkScore = blinkScore / 100f
        if (!t.start(ps)) {
            // 相機開不了（權限/模型）：停本次免得空轉，回設定頁看提示
            Toast.makeText(this, "相機/模型沒就緒，去設定頁檢查", Toast.LENGTH_SHORT).show()
            stop()
        } else {
            trackerHasPreview = ps != null
            coolStartAt = 0L // ponytail: 點火就緒不跑條，綠條只在真翻頁後跑
        }
    }

    private fun releaseCamera() {
        previewPending = false
        trackerHasPreview = false
        try { tracker?.stop() } catch (_: Exception) {}
        hidePreviewWindow()
    }

    // ---- gestures out ----
    private fun tapAt(fracX: Float) {
        val (w, h) = screenSize()
        val x = w * fracX
        val y = h * 0.5f
        // 只有 moveTo 是零長度手勢，部分 ROM 直接丟掉，補 1px 才會真的 dispatch
        val path = Path().apply { moveTo(x, y); lineTo(x + 1, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        if (!ok) stop()
    }

    private fun tapNextPage() {
        lastFlipAt = SystemClock.uptimeMillis()
        coolStartAt = lastFlipAt
        tapAt(0.8f) // 右中=下頁
    }
    private fun tapPrevPage() {
        lastFlipAt = SystemClock.uptimeMillis()
        coolStartAt = lastFlipAt
        tapAt(0.2f) // 左中=上頁
    }

    // ---- 懸浮球 ----
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ponytail: 位置直橫分開記，跟著當下方向讀寫
    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun savedPos(): Pair<Int, Int> = if (isLandscape()) pvXL to pvYL else pvXP to pvYP

    // ponytail: dispatchGesture 吃螢幕座標；displayMetrics 在橫屏/手勢列下會偏小，API 30+ 用真實螢幕尺寸
    private fun screenSize(): Pair<Int, Int> {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val w = getSystemService(WINDOW_SERVICE) as WindowManager
                val b = w.maximumWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
            }
        } catch (_: Exception) {}
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun showOverlay() {
        if (ball != null) {
            refreshBall()
            return
        }
        if (!Settings.canDrawOverlays(this)) return
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val (sw, sh) = screenSize()
        val b = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#80F2D98D"))
            }
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumWidth = dp(56)
            minimumHeight = dp(56)
        }
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = sw - dp(90)
            y = sh / 2
        }
        var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0; var moved = false
        val longPress = Runnable {
            if (!moved) {
                moved = true
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    baseX = bp.x; baseY = bp.y; moved = false
                    b.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (dx * dx + dy * dy > dp(10) * dp(10)) moved = true
                    if (moved) {
                        b.removeCallbacks(longPress)
                        bp.x = baseX + dx; bp.y = baseY + dy
                        wm?.updateViewLayout(v, bp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    b.removeCallbacks(longPress)
                    if (!moved) {
                        refreshForeground()
                        // 自家頁點球無反應（第一層，拖移/長按不受影響）
                        if (!inOwnApp) {
                            if (!serviceOn) {
                                Toast.makeText(this, "先去設定頁開啟翻頁服務", Toast.LENGTH_SHORT).show()
                            } else if (running) stop() else start()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> b.removeCallbacks(longPress)
            }
            true
        }
        ball = b
        ballParams = bp
        w.addView(b, bp)
        refreshBall()
    }

    private fun refreshBall() {
        ball?.text = if (active()) "❚❚" else "▶"
        ball?.alpha = ballAlpha / 100f
        ball?.visibility = if (overlayOn) View.VISIBLE else View.GONE
    }

    private fun hideOverlay() {
        hidePreviewWindow() // 先清預覽窗，wm 置空後就刪不掉了
        val w = wm ?: return
        ball?.let { b ->
            try { w.removeView(b) } catch (_: Exception) {}
        }
        ball = null
        ballParams = null
        wm = null
    }

    // ---- 懸浮預覽窗：跟相機同開同關，出入鏡一眼看出 ----
    private fun showPreviewWindow() {
        if (previewBox != null) return
        if (!Settings.canDrawOverlays(this)) return
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val maxW = (screenSize().first / 2).coerceAtLeast(dp(80))
        val initW = dp(previewWdp).coerceIn(dp(80), maxW)
        // ponytail: 鏡子靠 HAL 自帶（realme 實測舉右手像舉左手），App 多翻一次等於翻回來，故不翻
        val tv = android.view.TextureView(this).apply { alpha = previewAlpha / 100f }
        val overlay = HandOverlay(this)
        previewOverlay = overlay
        refreshOverlayGeom()
        val box = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            addView(tv, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            addView(overlay, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            // ponytail: 條子蓋最上層，不吃觸控（View 預設不 clickable，拖移照走）
            coolBar = View(this@PageTurnService).apply { setBackgroundColor(Color.parseColor("#8000E676")) }
            addView(coolBar, coolParams())
            refreshCoolBarGeom()
        }
        // ponytail: 初次 post 時 view 可能還沒量好寬高（fitPreview 直接 return = 鏡像沒設上）；
        // layout 穩定後再補一次，保證鏡像一定生效
        tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            (v as? android.view.TextureView)?.let {
                EyeTracker.fitPreview(it, EyeTracker.frontSensorDeg(this), EyeTracker.displayDeg(this))
            }
        }
        val pp = WindowManager.LayoutParams(
            initW, initW * 3 / 4,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            val (sw0, sh0) = screenSize()
            val (sx0, sy0) = savedPos()
            x = (if (sx0 >= 0) sx0 else dp(16)).coerceIn(0, (sw0 - initW).coerceAtLeast(0))
            y = (if (sy0 >= 0) sy0 else dp(100)).coerceIn(0, (sh0 - initW * 3 / 4).coerceAtLeast(0))
        }
        tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, ww: Int, hh: Int) {
                st.setDefaultBufferSize(EyeTracker.W, EyeTracker.H) // 固定 4:3，轉正矩陣才對得上
                previewSurface = android.view.Surface(st)
                if (previewPending) { previewPending = false; startTracker(previewSurface) }
                else if (tracker?.running == true && !trackerHasPreview) {
                    tracker?.stop() // 窗後建的（看門狗重建）：重啟把面接上
                    startTracker(previewSurface)
                }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, ww: Int, hh: Int) {
                tv.post { EyeTracker.fitPreview(tv, EyeTracker.frontSensorDeg(this@PageTurnService), EyeTracker.displayDeg(this@PageTurnService)) }
            }
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                previewSurface = null; return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        // ponytail: 本體拖移，右下角 40dp 熱區拖縮放（4:3，上限半屏；放開存檔）
        var lx = 0f; var ly = 0f; var sx = 0; var sy = 0; var sw0 = 0; var resizing = false
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.rawX; ly = e.rawY; sx = pp.x; sy = pp.y; sw0 = pp.width
                    resizing = e.x > box.width - dp(40) && e.y > box.height - dp(40)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lx).toInt(); val dy = (e.rawY - ly).toInt()
                    if (resizing) {
                        val mx = (screenSize().first / 2).coerceAtLeast(dp(80))
                        pp.width = (sw0 + dx).coerceIn(dp(80), mx)
                        pp.height = pp.width * 3 / 4
                    } else { pp.x = sx + dx; pp.y = sy + dy }
                    try { w.updateViewLayout(box, pp) } catch (_: Exception) {}
                    tv.post { EyeTracker.fitPreview(tv, EyeTracker.frontSensorDeg(this), EyeTracker.displayDeg(this)) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val e = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    if (resizing) {
                        previewWdp = (pp.width / resources.displayMetrics.density).toInt().coerceIn(80, 400)
                        e.putInt(KEY_PVW, previewWdp)
                    }
                    // ponytail: 抬手即存位置（直橫分槽），服務被殺也不丟
                    if (isLandscape()) { pvXL = pp.x; pvYL = pp.y; e.putInt(KEY_PVX_L, pp.x).putInt(KEY_PVY_L, pp.y) }
                    else { pvXP = pp.x; pvYP = pp.y; e.putInt(KEY_PVX_P, pp.x).putInt(KEY_PVY_P, pp.y) }
                    e.apply()
                    resizing = false
                }
            }
            true
        }
        previewBox = box
        previewParams = pp
        try {
            w.addView(box, pp)
            tv.post { EyeTracker.fitPreview(tv, EyeTracker.frontSensorDeg(this), EyeTracker.displayDeg(this)) }
            handler.removeCallbacks(coolTick)
            handler.post(coolTick)
        } catch (_: Exception) { previewBox = null; previewParams = null }
    }

    /** 疊加層幾何跟轉向走：旋轉/開關變化時重算，圈才一直套在手上。 */
    private fun refreshOverlayGeom() {
        previewOverlay?.apply {
            val disp = EyeTracker.displayDeg(this@PageTurnService)
            rotDeg = (((EyeTracker.frontSensorDeg(this@PageTurnService) - disp) % 360) + 360) % 360
            totalDeg = (360 - disp) % 360
        }
    }

    // ponytail: 直屏底橫條，橫屏左豎條；從起點長滿=冷卻走完
    private fun coolParams(): android.widget.FrameLayout.LayoutParams {
        val M = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        return if (isLandscape()) android.widget.FrameLayout.LayoutParams(dp(4), M, Gravity.START or Gravity.TOP)
        else android.widget.FrameLayout.LayoutParams(M, dp(4), Gravity.BOTTOM)
    }

    private fun refreshCoolBarGeom() {
        val b = coolBar ?: return
        val land = isLandscape()
        b.tag = land
        b.layoutParams = coolParams()
        b.pivotX = 0f; b.pivotY = 0f
        if (land) b.scaleX = 1f else b.scaleY = 1f
    }

    /** 設定頁改大小/透明度＋轉屏後重算（轉正＋透明度即時生效）。 */
    private fun refitPreview() {
        val box = previewBox as? android.widget.FrameLayout ?: return
        val pp = previewParams ?: return
        val maxW = (screenSize().first / 2).coerceAtLeast(dp(80))
        pp.width = dp(previewWdp).coerceIn(dp(80), maxW)
        pp.height = pp.width * 3 / 4
        try { wm?.updateViewLayout(box, pp) } catch (_: Exception) {}
        (box.getChildAt(0) as? android.view.TextureView)?.let { tv ->
            tv.alpha = previewAlpha / 100f
            tv.post { EyeTracker.fitPreview(tv, EyeTracker.frontSensorDeg(this), EyeTracker.displayDeg(this)) }
        }
        refreshOverlayGeom()
        refreshCoolBarGeom()
    }

    private fun hidePreviewWindow() {
        handler.removeCallbacks(coolTick)
        coolBar = null
        val w = wm
        previewBox?.let { b -> try { w?.removeView(b) } catch (_: Exception) {} }
        previewBox = null
        previewParams = null
        previewOverlay = null
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
    }
}
