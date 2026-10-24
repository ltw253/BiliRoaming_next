package me.iacn.biliroaming.utils

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * 记录最近 onResume 的 Activity。
 *
 * Application Context 没有窗口 token，直接拿它 show 对话框会抛
 * WindowManager.BadTokenException（表现为对话框静默不出现），
 * 因此需要对话框的场景必须使用 Activity Context。
 *
 * 这里只保存弱引用、不写任何日志，避免把界面类名或 URL 落进日志文件。
 */
object CurrentActivity {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    @Volatile
    private var installed = false

    val activity: Activity?
        get() = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            Activity::class.java.hookAfterMethod("onResume") { param ->
                (param.thisObject as? Activity)?.let { ref = WeakReference(it) }
            }
        }
    }
}
