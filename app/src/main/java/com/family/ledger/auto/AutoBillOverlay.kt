package com.family.ledger.auto

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.family.ledger.R
import com.family.ledger.core.Money
import com.family.ledger.data.db.entity.PendingBillEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 自动记账 · 悬浮确认卡片（钱迹式的「付款后自动弹出记账卡片」）。
 *
 * 这是**主交互路径**：识别到付款后，在微信/支付宝上方浮出一张不抢焦点的小卡片
 * 「¥45 · 美团 · 支付宝小荷包(示例日常)」，三个动作：
 *  - 记下   → [AutoBillPipeline.confirm] 直接入账（卡片收起 + 撤掉通知 + 轻提示）
 *  - 改一下 → 打开 [AutoBillConfirmActivity]（改资产/分类/时间）
 *  - ✕      → [AutoBillPipeline.ignore]（标记忽略，不建流水）
 *
 * 设计取舍：
 *  - **不抢焦点**（`FLAG_NOT_FOCUSABLE`）：用户在微信里打字不会被这张卡片打断；
 *  - **不常驻前台服务**：卡片用 `WindowManager` + `TYPE_APPLICATION_OVERLAY` 从
 *    `Application` 上下文直接 addView（manifest 已声明 `SYSTEM_ALERT_WINDOW`），
 *    卡片收起即 `removeView`，进程没事干时正常被系统回收，不额外耗电；
 *  - **自动收起但通知保留**：默认 12 秒无操作收起卡片，对应的「记一笔？」通知仍然留在通知栏（双保险）；
 *  - **队列**：同一时刻最多 1 张卡片，其余排队；卡片上显示「还有 N 笔待确认」。
 *
 * 没拿到「显示在其他应用上层」权限时，[show] 直接返回 false，由 [AutoBillPipeline] 降级为通知
 * —— **绝不因为没有浮窗权限就不记账**。
 *
 * 纯逻辑部分（[OverlayCard] / [OverlayText] / [OverlayPolicy] / [OverlayGeometry] / [OverlayTimer]
 * / [OverlayQueue]）刻意不碰任何 Android 类型，全部进 JVM 单测；[AutoBillOverlay] 才是 View 层。
 */
object AutoBillOverlay {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 待显示 / 正在显示的卡片；只在主线程访问（[visiblePendingId] 例外，见 @Volatile）。 */
    private val queue = OverlayQueue()

    @Volatile
    private var visibleCard: OverlayCard? = null

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var hostView: FrameLayout? = null
    private var hostParams: WindowManager.LayoutParams? = null
    private var waitingView: TextView? = null

    private var lastInteractionAt = 0L
    private var timeoutMs = OverlayPolicy.DEFAULT_TIMEOUT_MS

    /** 当前是否有卡片挂在屏幕上。 */
    val isShowing: Boolean get() = visibleCard != null

    /** 当前卡片对应的待确认账单 id（没有卡片时返回 null）。 */
    fun visiblePendingId(): String? = visibleCard?.pendingId

    /** 队列里还有几笔在等（不含正在显示的那笔）。状态在主线程维护，这里只做尽力读取。 */
    fun waitingCount(): Int = queue.waitingCount()

    // ---------- 对外入口 ----------

    /**
     * 这张待确认账单现在能不能弹卡片。
     *
     * 只看「权限 + 账单是否已被处理」，**不看用户开关**（开关由 [AutoBillPipeline] 判断，
     * 因为关掉开关时还要决定要不要提示一次去开权限）。
     */
    fun canShow(context: Context, pending: PendingBillEntity): Boolean =
        AutoBillPermission.canDrawOverlay(context) && !isResolved(pending)

    /**
     * 弹出（或排队）一张确认卡片。
     *
     * @return true = 已经交给浮窗渲染（可能排在别的卡片后面）；false = 弹不出来（没权限 / 已处理），
     *         调用方应降级为通知，**不要因此影响记账**。
     */
    fun show(context: Context, pending: PendingBillEntity, assetName: String?): Boolean {
        if (!canShow(context, pending)) return false
        val app = context.applicationContext
        val card = OverlayCard(
            pendingId = pending.id,
            amountCents = pending.amount,
            merchant = pending.merchant,
            assetName = assetName,
            duplicate = pending.status == PendingBillEntity.STATUS_DUPLICATE,
            transfer = PendingBillCodec.directionOf(pending) == Direction.TRANSFER,
        )
        // 必须在主线程操作 WindowManager
        main.post { enqueueCard(app, card) }
        return true
    }

    /**
     * 这笔账单已经在别处被处理（通知动作 / 确认页 / 自动入账）→ 撤掉对应卡片。
     * 幂等，线程安全（可从任意线程调用）。
     */
    fun dismiss(pendingId: String) {
        main.post {
            val wasVisible = visibleCard?.pendingId == pendingId
            queue.remove(pendingId)
            if (wasVisible) dismissCurrent() else updateWaitingBadge()
        }
    }

    /** 全部收起（例如用户关掉了「付款后自动弹出记账卡片」开关）。 */
    fun dismissAll() {
        main.post {
            queue.clear()
            dismissCurrent()
        }
    }

    /** 覆盖自动收起超时（默认 12 秒）；真机验证「是不是卡住不消失」时用得到。 */
    fun setTimeoutMs(value: Long) {
        timeoutMs = if (value > 0L) value else OverlayPolicy.DEFAULT_TIMEOUT_MS
    }

    // ---------- 主线程：队列与渲染 ----------

    private fun enqueueCard(app: Context, card: OverlayCard) {
        appContext = app
        // 这张卡已经挂在屏幕上（双通道上报同一笔）→ 不重复弹，也不重新计时
        if (visibleCard?.pendingId == card.pendingId) return
        queue.enqueue(card)
        if (visibleCard == null) showNext(app) else updateWaitingBadge()
    }

    private fun showNext(app: Context) {
        val next = queue.poll() ?: run {
            teardown()
            return
        }
        if (!attach(app, next)) {
            // 窗口挂不上（权限被撤销 / 系统拒绝）：再试下一张也没意义，清空队列静默降级为通知
            queue.clear()
            teardown()
            return
        }
        visibleCard = next
        lastInteractionAt = SystemClock.uptimeMillis()
        scheduleTimeout()
    }

    /** @return true = 卡片已经挂到屏幕上 */
    private fun attach(app: Context, card: OverlayCard): Boolean {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        removeHostOnly()

        val metrics = app.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val bottomInset = OverlayGeometry.navigationBarHeight(app)
        // 窗口宽度 = 屏幕宽 - 左右留白，让窗口矩形和卡片一样大：
        // 若用 MATCH_PARENT，窗口两侧会多出一条「点了没反应」的死区（触摸被我们的窗口吃掉，传不到微信/支付宝）。
        val margin = app.dp(OverlayPolicy.SIDE_MARGIN_DP.toFloat())
        val windowWidth = (screenWidth - margin * 2).coerceAtLeast(app.dp(180f))

        val view = buildCardView(app, card)
        // 先按窗口宽量一次，拿到卡片真实高度后再算 y —— 避免先出现在错误位置再跳一下
        runCatching {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(windowWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
        }
        val cardHeight = view.measuredHeight.takeIf { it > 0 }
            ?: (OverlayPolicy.ESTIMATED_CARD_HEIGHT_DP * view.density()).toInt()

        val lp = WindowManager.LayoutParams(
            windowWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 关键：不抢焦点（否则用户在微信里打字会被打断）+ 不阻塞卡片外的触摸
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = margin
            y = OverlayGeometry.verticalOffset(screenHeight, cardHeight, bottomInset)
        }

        val ok = runCatching { wm.addView(view, lp) }.isSuccess
        if (!ok) {
            // 权限被撤销 / 窗口被系统拒绝：静默失败，通知还在
            return false
        }
        windowManager = wm
        hostView = view
        hostParams = lp
        // 真机首次测量高度可能为 0，挂上去后再校正一次位置
        view.post {
            val h = view.height
            if (h > 0) {
                lp.y = OverlayGeometry.verticalOffset(metrics.heightPixels, h, bottomInset)
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
        return true
    }

    private fun dismissCurrent() {
        main.removeCallbacks(timeoutRunnable)
        teardown()
        visibleCard = null
        val app = appContext ?: return
        // 队列里还有人等 → 留一点间隔再弹下一张（这里不预先 poll，避免和延迟期间新入队的卡片抢）
        if (queue.size() == 0) return
        main.postDelayed({
            if (visibleCard == null && queue.size() > 0) showNext(app)
        }, OverlayPolicy.NEXT_CARD_DELAY_MS)
    }

    private fun teardown() {
        removeHostOnly()
        waitingView = null
    }

    private fun removeHostOnly() {
        val wm = windowManager
        val view = hostView
        if (wm != null && view != null) runCatching { wm.removeViewImmediate(view) }
        hostView = null
        hostParams = null
    }

    // ---------- 计时 ----------

    private fun noteInteraction() {
        lastInteractionAt = SystemClock.uptimeMillis()
    }

    private fun scheduleTimeout() {
        main.removeCallbacks(timeoutRunnable)
        main.postDelayed(timeoutRunnable, timeoutMs)
    }

    /**
     * 12 秒无操作 → 收起卡片。
     * **注意**：这里只收卡片，不撤通知 —— 用户还能从通知栏点「记入家庭账本」。
     */
    private val timeoutRunnable: Runnable = Runnable {
        val now = SystemClock.uptimeMillis()
        if (OverlayTimer.shouldAutoDismiss(lastInteractionAt, now, timeoutMs)) {
            dismissCurrent()
        } else {
            main.postDelayed(timeoutRunnable, OverlayTimer.remainingMs(lastInteractionAt, now, timeoutMs))
        }
    }

    // ---------- 三个动作 ----------

    private fun onConfirm(app: Context, card: OverlayCard) {
        // 先收卡片，避免用户连点两次；confirm() 本身幂等
        dismissCurrent()
        scope.launch {
            val pipeline = AutoBillPipeline.getOrNull(app)
            val txnId = pipeline?.confirm(card.pendingId)
            if (txnId != null) {
                main.post { toast(app, app.getString(R.string.auto_notif_saved)) }
            } else if (card.transfer) {
                // 转账认不出「转入账户」时 confirm() 会拒绝入账（宁可不记也不能记错余额）：
                // 明确告诉用户，并直接把他带到确认页选一次转入账户。
                main.post {
                    toast(app, app.getString(R.string.auto_transfer_need_target))
                    openConfirmActivity(app, card)
                }
            }
        }
    }

    private fun onChange(app: Context, card: OverlayCard) = openConfirmActivity(app, card)

    private fun openConfirmActivity(app: Context, card: OverlayCard) {
        dismissCurrent()
        val intent = AutoBillConfirmActivity.intent(app, card.pendingId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // 已授予 SYSTEM_ALERT_WINDOW 时允许从后台启动 Activity；再兜一层异常，绝不崩
        runCatching { app.startActivity(intent) }
    }

    private fun onIgnore(app: Context, card: OverlayCard) {
        dismissCurrent()
        scope.launch { AutoBillPipeline.getOrNull(app)?.ignore(card.pendingId) }
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    // ---------- 视图 ----------

    private fun buildCardView(app: Context, card: OverlayCard): FrameLayout {
        // 拖动时需要知道屏幕高度与导航栏高度，这里取一次并捕获进触摸监听
        val screenHeight = app.resources.displayMetrics.heightPixels
        val shadowPad = app.dp(4f)
        val host = FrameLayout(app).apply {
            // 留一点边给 elevation 阴影（窗口矩形=卡片矩形，阴影本来会被窗口边界裁掉）
            setPadding(shadowPad, shadowPad, shadowPad, shadowPad)
        }
        val cardView = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, app.dp(22f))
            elevation = app.dp(8f).toFloat()
            // 用户反馈「弹出界面太小太挤」→ 整体放大：内边距 14/10/12/10 → 20/18/16/18
            setPadding(app.dp(20f), app.dp(18f), app.dp(16f), app.dp(18f))
        }
        host.addView(
            cardView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // ---- 第一行：金额 · 商户 · 资产 + ✕ ----
        val title = TextView(app).apply {
            text = OverlayText.summary(
                amountCents = card.amountCents,
                merchant = card.merchant,
                assetName = card.assetName,
                suffix = if (card.duplicate) app.getString(R.string.auto_notif_duplicate) else null,
                prefix = if (card.transfer) app.getString(R.string.auto_overlay_transfer_prefix) else null,
            )
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(app.dp(2f).toFloat(), 1f)
        }
        val close = TextView(app).apply {
            text = app.getString(R.string.auto_overlay_dismiss)
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            // 触摸目标 ≥44dp（Material 最小点击区），避免「点不中」
            setPadding(app.dp(12f), app.dp(6f), app.dp(8f), app.dp(6f))
            isClickable = true
            contentDescription = app.getString(R.string.auto_overlay_dismiss_desc)
            setOnClickListener { onIgnore(app, card) }
        }
        cardView.addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    title,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(close, LinearLayout.LayoutParams(-2, -2))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // ---- 第二行：「还有 N 笔待确认」 + 改一下 + 记下 ----
        val waiting = TextView(app).apply {
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = View.GONE
        }
        waitingView = waiting

        val change = pillButton(
            app = app,
            text = app.getString(R.string.auto_overlay_change),
            bg = COLOR_PILL_OUTLINE,
            fg = COLOR_TEXT,
            stroke = COLOR_STROKE,
        ) { onChange(app, card) }

        val confirm = pillButton(
            app = app,
            text = app.getString(R.string.auto_overlay_confirm),
            bg = COLOR_PRIMARY,
            fg = Color.WHITE,
            stroke = null,
        ) { onConfirm(app, card) }

        cardView.addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, app.dp(14f), 0, 0)
                addView(
                    waiting,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(change, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = app.dp(8f) })
                addView(confirm, LinearLayout.LayoutParams(-2, -2))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // ---- 拖动：手指落在卡片空白处（非按钮）时纵向拖动，避开支付结果的金额/按钮区 ----
        cardView.setOnTouchListener(object : View.OnTouchListener {
            private var startRawY = 0f
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val wm = windowManager ?: return false
                val lp = hostParams ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawY = e.rawY
                        startY = lp.y
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dy = (e.rawY - startRawY).toInt()
                        if (!dragging && abs(dy) < app.dp(6f)) return true
                        dragging = true
                        lp.y = OverlayGeometry.clampVertical(
                            y = startY + dy,
                            screenHeight = screenHeight,
                            cardHeight = v.height,
                            bottomInset = OverlayGeometry.navigationBarHeight(app),
                        )
                        runCatching { wm.updateViewLayout(host, lp) }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 拖动也算「一次操作」，重新开始 12 秒倒计时
                        if (dragging) {
                            noteInteraction()
                            scheduleTimeout()
                        }
                        return true
                    }
                }
                return false
            }
        })

        updateWaitingBadge()
        return host
    }

    /** 队列里还有别的账单时，在卡片上提示「还有 N 笔待确认」。 */
    private fun updateWaitingBadge() {
        val view = waitingView ?: return
        val waiting = queue.waitingCount()
        if (waiting <= 0) {
            view.visibility = View.GONE
            return
        }
        view.text = appContext?.getString(R.string.auto_overlay_pending_more, waiting).orEmpty()
        view.visibility = View.VISIBLE
    }

    private fun pillButton(
        app: Context,
        text: String,
        bg: Int,
        fg: Int,
        stroke: Int?,
        onClick: () -> Unit,
    ): TextView = TextView(app).apply {
        this.text = text
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        // 按钮做大：内边距 14x7 → 22x13（高度约 48dp，好按）
        setPadding(app.dp(22f), app.dp(13f), app.dp(22f), app.dp(13f))
        background = roundedBackground(bg, app.dp(24f), stroke, app.dp(1f))
        gravity = android.view.Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun roundedBackground(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != null && strokeWidth > 0) setStroke(strokeWidth, stroke)
        }

    private fun isResolved(pending: PendingBillEntity): Boolean =
        pending.status == PendingBillEntity.STATUS_CONFIRMED ||
            pending.status == PendingBillEntity.STATUS_IGNORED

    // 颜色：跟 Material3 主题的主色 #2E7D6B 对齐，但浮窗是系统级 View，不依赖 Compose 主题
    private const val COLOR_CARD = 0xF2FFFFFF.toInt()
    private const val COLOR_TEXT = 0xFF1F2937.toInt()
    private const val COLOR_MUTED = 0xFF9AA3AF.toInt()
    private const val COLOR_ACCENT = 0xFFB45309.toInt()
    private const val COLOR_PRIMARY = 0xFF2E7D6B.toInt()
    private const val COLOR_PILL_OUTLINE = 0xFFFFFFFF.toInt()
    private const val COLOR_STROKE = 0xFFD8DEE6.toInt()

    private fun Context.dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun View.density(): Float = resources.displayMetrics.density
}

/** 一张待确认卡片需要展示的数据（纯数据，可单测）。 */
data class OverlayCard(
    val pendingId: String,
    val amountCents: Long,
    val merchant: String?,
    val assetName: String?,
    val duplicate: Boolean = false,
    /** 自己账户之间的转账（卡片上标一下「转账」，免得用户以为又是一笔支出）。 */
    val transfer: Boolean = false,
)

/** 卡片文案（纯函数，可单测）。 */
object OverlayText {

    private const val SEP = " · "

    /** `¥45 · 美团 · 支付宝小荷包(示例日常)`，空字段自动跳过，重复账单追加提示。 */
    fun summary(
        amountCents: Long,
        merchant: String?,
        assetName: String?,
        suffix: String? = null,
        prefix: String? = null,
    ): String =
        buildString {
            prefix?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append(' ') }
            append(Money.format(amountCents))
            merchant?.takeIf { it.isNotBlank() }?.let { append(SEP).append(it.trim()) }
            assetName?.takeIf { it.isNotBlank() }?.let { append(SEP).append(it.trim()) }
            suffix?.takeIf { it.isNotBlank() }?.let { append(SEP).append(it.trim()) }
        }
}

/**
 * 弹卡片的策略（纯函数，可单测）。
 *
 * 降级链：开关关 → 什么都不做；没权限 → 只发通知；有权限 → 卡片 + 通知双保险。
 */
object OverlayPolicy {

    /** 默认 12 秒无操作收起。 */
    const val DEFAULT_TIMEOUT_MS = 12_000L

    /** 前一张收起后，等一小会儿再弹下一张（避免视觉上「跳一下」）。 */
    const val NEXT_CARD_DELAY_MS = 350L

    /** 卡片高度的估算值（真实高度拿不到时用来算 y）。 */
    const val ESTIMATED_CARD_HEIGHT_DP = 132

    /** 卡片与屏幕左右边缘的留白（窗口宽度 = 屏幕宽 - 2 × 这个值）。 */
    const val SIDE_MARGIN_DP = 16

    /** 是否应该弹浮窗卡片。 */
    fun shouldShow(popupEnabled: Boolean, canDrawOverlay: Boolean, pendingResolved: Boolean): Boolean =
        popupEnabled && canDrawOverlay && !pendingResolved

    /**
     * 是否应该提示「去开悬浮窗权限」。
     *
     * 只在「用户开着弹卡片开关 + 确实没权限 + 从没提示过」时为真 —— 不反复烦用户。
     */
    fun shouldHintMissingPermission(popupEnabled: Boolean, canDrawOverlay: Boolean, hintShown: Boolean): Boolean =
        popupEnabled && !canDrawOverlay && !hintShown
}

/** 卡片位置计算（纯函数，可单测）。 */
object OverlayGeometry {

    /** 纵向锚点：屏幕高度的 62% —— 在支付结果金额下方、微信/支付宝底部按钮上方。 */
    const val VERTICAL_ANCHOR = 0.62f

    /** 卡片纵向位置：按屏幕高度比例居中，再夹到「不越界」的区间。 */
    fun verticalOffset(screenHeight: Int, cardHeight: Int, bottomInset: Int): Int {
        if (screenHeight <= 0) return 0
        val anchor = (screenHeight * VERTICAL_ANCHOR).toInt() - cardHeight / 2
        return clampVertical(anchor, screenHeight, cardHeight, bottomInset)
    }

    /** 拖动后的位置夹取：不能拖出屏幕上/下边界。 */
    fun clampVertical(y: Int, screenHeight: Int, cardHeight: Int, bottomInset: Int): Int {
        val maxY = (screenHeight - cardHeight - bottomInset).coerceAtLeast(0)
        return y.coerceIn(0, maxY)
    }

    /** 系统导航栏高度（拿不到就 0，不影响主流程）。 */
    fun navigationBarHeight(context: Context): Int = runCatching {
        val res = context.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) res.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)
}

/** 自动收起计时（纯函数，可单测）。 */
object OverlayTimer {

    /** 距上次操作是否已经超过 timeout。 */
    fun shouldAutoDismiss(lastInteractionAt: Long, now: Long, timeoutMs: Long): Boolean =
        timeoutMs > 0L && now - lastInteractionAt >= timeoutMs

    /** 还要等多久（用于重新排定时器，永不返回负数）。 */
    fun remainingMs(lastInteractionAt: Long, now: Long, timeoutMs: Long): Long =
        (timeoutMs - (now - lastInteractionAt)).coerceAtLeast(0L)
}

/**
 * 卡片队列：同一时刻最多显示 1 张，其余排队；同一笔账单重复上报只更新内容、不重复排队。
 *
 * 约定：**正在显示的那张已经不在队列里**（由 [poll] 取走），队列里只剩「还在等」的卡片。
 * 纯逻辑，可单测。
 */
class OverlayQueue {

    private val items = ArrayDeque<OverlayCard>()

    /** 入队；同一 pendingId 已存在时原地更新内容。返回 true 表示队列长度增加了。 */
    fun enqueue(card: OverlayCard): Boolean {
        val index = items.indexOfFirst { it.pendingId == card.pendingId }
        if (index >= 0) {
            items[index] = card
            return false
        }
        items.addLast(card)
        return true
    }

    /** 下一张要显示的卡片（不取走）。 */
    fun peek(): OverlayCard? = items.firstOrNull()

    /** 取出下一张来显示；显示中的卡片从此不在队列里。 */
    fun poll(): OverlayCard? = items.removeFirstOrNull()

    fun remove(pendingId: String): Boolean = items.removeAll { it.pendingId == pendingId }

    fun size(): Int = items.size

    /** 还有几笔在等（卡片上「还有 N 笔待确认」；不含正在显示的那笔）。 */
    fun waitingCount(): Int = items.size

    fun clear() = items.clear()

    fun ids(): List<String> = items.map { it.pendingId }
}
