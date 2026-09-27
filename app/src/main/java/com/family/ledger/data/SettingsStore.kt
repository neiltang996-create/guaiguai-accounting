package com.family.ledger.data

import android.content.Context
import com.family.ledger.BuildConfig
import com.family.ledger.core.Ids

/**
 * 极简设置存储（SharedPreferences）。
 * 只放本机身份与开关，业务数据一律进 Room。
 */
class SettingsStore(context: Context, preferenceName: String = "family_ledger_prefs") {

    private val sp = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    /** 本机设备 ID，安装后即固定，用于同步与「我」的识别。 */
    val deviceId: String
        get() = synchronized(DEVICE_LOCK) {
            sp.getString(KEY_DEVICE_ID, null) ?: Ids.newId("dev-").also {
                check(sp.edit().putString(KEY_DEVICE_ID, it).commit())
            }
        }

    var myMemberId: String?
        get() = sp.getString(KEY_MY_MEMBER_ID, null)
        set(v) = sp.edit().putString(KEY_MY_MEMBER_ID, v).apply()

    var myDisplayName: String
        get() = sp.getString(KEY_MY_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_MY_NAME, v).apply()

    var familyId: String?
        get() = sp.getString(KEY_FAMILY_ID, null)
        set(v) = sp.edit().putString(KEY_FAMILY_ID, v).apply()

    var bootstrapDone: Boolean
        get() = sp.getBoolean(KEY_BOOTSTRAP, false)
        set(v) = sp.edit().putBoolean(KEY_BOOTSTRAP, v).apply()

    var debugCaptureEnabled: Boolean
        get() = sp.getBoolean("debug_capture", false)
        set(v) = sp.edit().putBoolean("debug_capture", v).apply()

    var receiptOcrEnabled: Boolean
        get() = !BuildConfig.DEMO_MODE && sp.getBoolean("receipt_ocr", false)
        set(v) = sp.edit().putBoolean("receipt_ocr", v).apply()

    var autoBillEnabled: Boolean
        get() = !BuildConfig.DEMO_MODE && sp.getBoolean(KEY_AUTO_BILL, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_BILL, v).apply()

    var autoBillAutoCommit: Boolean
        get() = sp.getBoolean(KEY_AUTO_COMMIT, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO_COMMIT, v).apply()

    /**
     * 付款后是否自动弹出记账卡片（浮窗）。
     * 默认打开。需要「显示在其他应用上层」权限；没给权限时自动降级为通知。
     */
    var autoBillPopupEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_POPUP, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_POPUP, v).apply()

    /** 「需要开启悬浮窗权限」的提示是否已经弹过（只提示一次，不烦用户）。 */
    var autoBillPopupHintShown: Boolean
        get() = sp.getBoolean(KEY_AUTO_POPUP_HINT, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO_POPUP_HINT, v).apply()

    /**
     * 首启播种（分类/资产）是否**完整跑完过一次**。
     *
     * 不能只看「表是不是空的」：冷启动首次写 146 条要好几秒，用户此时划掉 App
     * （真机很常见）会留下「只写了一部分」的状态，而 `categoryDao.count() != 0`
     * 会让下次启动**再也不补种** —— 用户会看到分类残缺且永远不会好。
     */
    var bootstrapSeeded: Boolean
        get() = sp.getBoolean(KEY_BOOTSTRAP_SEEDED, false)
        set(v) = sp.edit().putBoolean(KEY_BOOTSTRAP_SEEDED, v).apply()

    /**
     * 本机使用者是否已经选过「我是谁」。
     *
     * 家里只有两个人，所以刻意不做登录、不做家庭码：
     * 首次打开选一次「用户 A」或「用户 B」即可，之后一切自动。
     */
    var identityChosen: Boolean
        get() = sp.getBoolean(KEY_IDENTITY_CHOSEN, false)
        set(v) = sp.edit().putBoolean(KEY_IDENTITY_CHOSEN, v).apply()

    /** 同步：WebDAV 目录 URL。 */
    var syncUrl: String
        get() = sp.getString(KEY_SYNC_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_SYNC_URL, v).apply()

    var syncUser: String
        get() = sp.getString(KEY_SYNC_USER, "") ?: ""
        set(v) = sp.edit().putString(KEY_SYNC_USER, v).apply()

    var syncPassword: String
        get() = sp.getString(KEY_SYNC_PASS, "") ?: ""
        set(v) = sp.edit().putString(KEY_SYNC_PASS, v).apply()

    var syncEnabled: Boolean
        get() = !BuildConfig.DEMO_MODE && sp.getBoolean(KEY_SYNC_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_SYNC_ENABLED, v).apply()

    var lastSyncAt: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_SYNC, v).apply()

    var lastSyncMessage: String
        get() = sp.getString(KEY_LAST_SYNC_MSG, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_SYNC_MSG, v).apply()

    /**
     * 家庭同步服务器开关（公共构建默认关闭）。
     *
     * 名字保留 `lanSync` 是历史原因，**语义已扩展为「自动同步」**：
     * 家庭里有一台常驻的同步服务器（NAS），两台手机不管在家还是在外都连它，
     * 用户不需要填任何地址、账号、密码。
     */
    var lanSyncEnabled: Boolean
        get() = !BuildConfig.DEMO_MODE && sp.getBoolean(KEY_LAN_SYNC, false)
        set(v) = sp.edit().putBoolean(KEY_LAN_SYNC, v).apply()

    /** 家庭里显示给对方看的设备名，例如「用户 A的手机」。 */
    var myDeviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_DEVICE_NAME, v).apply()

    /**
     * 家庭同步服务器地址覆盖。
     *
     * 空字符串 = 用 App 内置的候选地址自动探测（局域网 → 公网），
     * 也就是**用户什么都不用填**。只有想换服务器时才需要填。
     */
    var homeServerUrl: String
        get() = sp.getString(KEY_HOME_SERVER_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOME_SERVER_URL, v).apply()

    /** 同步服务器 token（服务端配了 FAMILYLEDGER_TOKEN 时用）。 */
    var homeServerToken: String
        get() = sp.getString(KEY_HOME_SERVER_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOME_SERVER_TOKEN, v).apply()

    private companion object {
        val DEVICE_LOCK = Any()
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_MY_MEMBER_ID = "my_member_id"
        const val KEY_MY_NAME = "my_name"
        const val KEY_FAMILY_ID = "family_id"
        const val KEY_BOOTSTRAP = "bootstrap_done"
        const val KEY_AUTO_BILL = "auto_bill_enabled"
        const val KEY_AUTO_COMMIT = "auto_bill_auto_commit"
        const val KEY_SYNC_URL = "sync_url"
        const val KEY_SYNC_USER = "sync_user"
        const val KEY_SYNC_PASS = "sync_pass"
        const val KEY_SYNC_ENABLED = "sync_enabled"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LAST_SYNC_MSG = "last_sync_msg"
        const val KEY_AUTO_POPUP = "auto_bill_popup_enabled"
        const val KEY_AUTO_POPUP_HINT = "auto_bill_popup_hint_shown"
        const val KEY_IDENTITY_CHOSEN = "identity_chosen"
        const val KEY_BOOTSTRAP_SEEDED = "bootstrap_seeded"
        const val KEY_LAN_SYNC = "lan_sync_enabled"
        const val KEY_DEVICE_NAME = "my_device_name"
        const val KEY_HOME_SERVER_URL = "home_server_url"
        const val KEY_HOME_SERVER_TOKEN = "home_server_token"
    }
}
