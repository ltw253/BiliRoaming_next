package me.iacn.biliroaming.hook

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import java.lang.ref.WeakReference

/**
 * 移除「我的」页里的大会员模组。
 *
 * 解包依据（9.8.0）：
 *  - 主布局 `bili_layout_main_user_center` 通过 `<include id=0x7f092fa9>` 引入
 *    `bili_layout_mine_vip_module_refactoring`；
 *  - 渲染入口是 `MineVipModuleManager$renderEnums$2.invokeSuspend`。
 *
 * 视觉处理：
 *  - 保留 include 占位（头部装扮区不塌陷），隐藏横幅子 View；
 *  - 遮盖色**从页面实际背景取样**（沿父链查找 ColorDrawable → decor → 主题），
 *    避免与下方内容色不一致；
 *  - 覆盖层为「透明 → 中间半透明 → 纯色」的加强渐变 + 底部圆角卡片；
 *  - 监听 onConfigurationChanged，深浅色切换立即刷新颜色。
 */
class VipSectionHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private var coverRootRef: WeakReference<View>? = null

    override fun startHook() {
        if (!sPrefs.getBoolean("hidden", false)
            || !sPrefs.getBoolean("remove_vip_section", false)
        ) {
            Log.d("VipSection: 开关未开启，跳过（hidden/remove_vip_section）")
            return
        }

        // 1) 跳过大会员模块渲染
        val renderStateClass =
            "tv.danmaku.bili.ui.main2.mine.modularvip.MineVipModuleManager\$renderEnums\$2"
                .findClassOrNull(mClassLoader)
        val renderHook = renderStateClass?.declaredMethods
            ?.firstOrNull { it.name == "invokeSuspend" }
        if (renderHook != null) {
            renderHook.hookBeforeMethod { param -> param.result = Unit }
            Log.d("VipSection: 已跳过大会员模块渲染")
        } else {
            Log.d("VipSection: 未找到 renderEnums 渲染入口")
        }

        val fragmentClass = instance.homeUserCenterClass
            ?: "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"
                .findClassOrNull(mClassLoader)
        if (fragmentClass == null) {
            Log.d("VipSection: 未找到「我的」页 Fragment")
            return
        }

        // 2) 页面创建 / 恢复时应用遮盖
        fragmentClass.hookAfterMethod(
            "onViewCreated",
            View::class.java,
            Bundle::class.java
        ) { param ->
            val view = param.args[0] as? View ?: return@hookAfterMethod
            runCatching { applyVipCover(view) }
                .onFailure { Log.e("VipSection: 遮盖失败 $it") }
            view.post {
                runCatching { applyVipCover(view) }
                    .onFailure { Log.e("VipSection: 遮盖失败(post) $it") }
            }
        }
        fragmentClass.hookAfterMethod("onResume") { param ->
            val view = param.thisObject?.callMethodAs<View?>("getView")
                ?: return@hookAfterMethod
            view.post {
                runCatching { applyVipCover(view) }
                    .onFailure { Log.e("VipSection: 恢复时遮盖失败 $it") }
            }
        }

        // 3) 深浅色切换立即生效
        Activity::class.java.hookAfterMethod(
            "onConfigurationChanged",
            Configuration::class.java
        ) { _ ->
            val root = coverRootRef?.get() ?: return@hookAfterMethod
            if (!root.isAttachedToWindow) return@hookAfterMethod
            root.post {
                runCatching { applyVipCover(root) }
                    .onFailure { Log.e("VipSection: 主题切换重新遮盖失败 $it") }
                Log.d("VipSection: 主题切换后已刷新遮盖")
            }
        }
    }

    private fun applyVipCover(root: View) {
        val vipContainer = root.findViewById<View>(ID_VIP_INCLUDE) ?: run {
            Log.d("VipSection: 未找到大会员模块容器")
            return
        }
        coverRootRef = WeakReference(root)
        (vipContainer as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child.visibility != View.INVISIBLE) child.visibility = View.INVISIBLE
            }
        }

        val resources = vipContainer.resources

        val isNight =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        val backgroundColor = resolveBackgroundColor(root, isNight)

        // 回到 dev12 的简单形式：整块区域一层渐变覆盖。
        // 用「平滑曲线 + 多色标 + 抖动」的自绘 Drawable，避免三色标线性渐变
        // 在暗色纯色背景上出现的可见色带。
        vipContainer.background = SmoothFadeDrawable(backgroundColor)
        Log.d(
            "VipSection: 已应用平滑渐变遮盖 night=$isNight " +
                    "color=#${Integer.toHexString(backgroundColor)}"
        )
    }

    /**
     * 透明 → 纯色的平滑渐变。
     *
     * 采用 smoothstep 曲线生成 48 个色标，并开启 dither，消除线性 alpha 渐变
     * 在暗色背景上的色带（banding）。
     */
    private class SmoothFadeDrawable(private val targetColor: Int) :
        android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint().apply { isDither = true }

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            val stopCount = 48
            val colors = IntArray(stopCount)
            val positions = FloatArray(stopCount)
            for (index in 0 until stopCount) {
                val t = index.toFloat() / (stopCount - 1)
                val eased = t * t * (3f - 2f * t)
                val alpha = (255 * eased).toInt().coerceIn(0, 255)
                colors[index] = (targetColor and 0x00FFFFFF) or (alpha shl 24)
                positions[index] = t
            }
            paint.shader = android.graphics.LinearGradient(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.left.toFloat(),
                bounds.bottom.toFloat(),
                colors,
                positions,
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: android.graphics.Canvas) {
            canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 沿视图父链取样页面实际背景色，取不到时退回主题色。 */
    private fun resolveBackgroundColor(root: View, isNight: Boolean): Int {
        var current: View? = root
        var depth = 0
        while (current != null && depth < 8) {
            colorOf(current.background)?.let { return it }
            current = current.parent as? View
            depth++
        }
        val activity = root.context as? Activity
        colorOf(activity?.window?.decorView?.background)?.let { return it }
        runCatchingOrNull {
            val typedValue = android.util.TypedValue()
            if (activity?.theme?.resolveAttribute(
                    android.R.attr.colorBackground, typedValue, true
                ) == true
            ) {
                typedValue.data
            } else {
                null
            }
        }?.let { return it }
        return if (isNight) 0xFF121212.toInt() else Color.WHITE
    }

    private fun colorOf(drawable: android.graphics.drawable.Drawable?): Int? =
        runCatchingOrNull { (drawable as? ColorDrawable)?.color }

    companion object {
        /** 从 9.8.0 布局 bili_layout_main_user_center 解包确认的 include id。 */
        private const val ID_VIP_INCLUDE = 0x7f092fa9
    }
}
