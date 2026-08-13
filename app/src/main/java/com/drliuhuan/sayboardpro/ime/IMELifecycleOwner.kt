package com.drliuhuan.sayboardpro.ime

import android.app.Application
import android.view.View
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * IME 窗口的 Compose 宿主 owner。
 *
 * InputMethodService 的窗口没有宿主 Activity，而 Compose 的 AbstractComposeView 在组合时
 * 要求从 view 树中解析出 LifecycleOwner、ViewModelStoreOwner 与 SavedStateRegistryOwner
 * （AndroidX 通过 decorView 上注册的 ViewTree*Owner 查找，见
 * androidx.lifecycle.setViewTreeLifecycleOwner 等）。本类把自身注册为这三个 owner，并用
 * LifecycleRegistry 状态机在窗口显示/隐藏时驱动生命周期：窗口显示 -> RESUMED，
 * 窗口隐藏 -> 回到 CREATED，销毁 -> DESTROYED。
 * 参考 Android 官方 InputMethodService 与 Compose 集成文档。
 */
class IMELifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    SavedStateRegistryOwner {

    // ── 状态：生命周期状态机 / ViewModelStore / SavedState 控制器 ──────────────

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    private val store = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    /**
     * 从 IME 窗口 decorView 的 context 捕获的 Application（见 [attachToDecorView]），
     * 用于填充 [defaultViewModelCreationExtras]，使 Compose 里的 AndroidViewModel 能拿到
     * application 构造参数；窗口 attach 前为 null，此时 extras 退化为空。
     */
    private var application: Application? = null

    // ── Owner 接口实现 ────────────────────────────────────────────────────────

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    /**
     * ViewModel 的默认创建参数：注入 APPLICATION_KEY，供 AndroidViewModelFactory 构造
     * 带 application 的 ViewModel；拿不到 application 时返回空 extras。
     */
    override val defaultViewModelCreationExtras: CreationExtras
        get() {
            val app = application ?: return CreationExtras.Empty
            return MutableCreationExtras().apply {
                set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
            }
        }

    /**
     * 与 [defaultViewModelCreationExtras] 配套的默认工厂：有 application 时用
     * AndroidViewModelFactory（可构造 AndroidViewModel），否则退化为 NewInstanceFactory。
     */
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() {
            val app = application
            return if (app != null) {
                ViewModelProvider.AndroidViewModelFactory(app)
            } else {
                ViewModelProvider.NewInstanceFactory()
            }
        }

    // ── 生命周期驱动（由 IME 在对应回调中调用）────────────────────────────────

    /**
     * IME.onCreate：恢复（无）SavedState 并驱动到 CREATED -> STARTED。
     * IME 窗口创建后随即显示，提前到 STARTED 保证键盘 view 首次 attach 时 Compose
     * 即可立即组合（AbstractComposeView 要求生命周期至少 STARTED）。
     */
    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    /** IME.onWindowShown：窗口显示，驱动到 RESUMED。重复调用安全（先确保 STARTED）。 */
    fun onResume() {
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    /** IME.onWindowHidden：窗口隐藏，驱动回 CREATED（Compose 停止组合并释放资源）。 */
    fun onPause() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    /** IME.onDestroy：驱动到 DESTROYED 并清空 ViewModelStore。重复调用安全。 */
    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    // ── ViewTree 注册 ─────────────────────────────────────────────────────────

    /**
     * 把本类注册为 IME 窗口 decorView 的 ViewTree Lifecycle/ViewModel/SavedStateRegistry
     * owner，Compose 组合时据此定位宿主。同时从 decorView 的 context 提取 Application
     * 供 [defaultViewModelCreationExtras] / [defaultViewModelProviderFactory] 使用。
     */
    fun attachToDecorView(decorView: View?) {
        if (decorView == null) return
        application = decorView.context.applicationContext as? Application ?: application
        decorView.setViewTreeLifecycleOwner(this)
        decorView.setViewTreeViewModelStoreOwner(this)
        decorView.setViewTreeSavedStateRegistryOwner(this)
    }
}
