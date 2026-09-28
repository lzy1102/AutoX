package com.stardust.autojs.rhino

import android.os.Looper
import android.util.Log
import com.stardust.autojs.runtime.ScriptBridges
import com.stardust.autojs.runtime.exception.ScriptInterruptedException
import com.stardust.automator.UiObject
import com.stardust.automator.UiObjectCollection
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by Stardust on 2017/4/5.
 */
open class AndroidContextFactory(private val cacheDirectory: File) : ContextFactory() {
    companion object {
        const val LOG_TAG = "ContextFactory"
        val bridges = ScriptBridges()
    }

    private val mContextCount = AtomicInteger()
    private val wrapFactory = WrapFactory()

    init {
        initApplicationClassLoader(createClassLoader(AndroidContextFactory::class.java.classLoader!!))
    }

    /**
     * Create a ClassLoader which is able to deal with bytecode
     *
     * @param parent the parent of the create classloader
     * @return a new ClassLoader
     */
    final override fun createClassLoader(parent: ClassLoader): AndroidClassLoader {
        return AndroidClassLoader(parent, cacheDirectory)
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // UI 模式的脚本直接运行在 Android 主线程上：不能依赖线程中断标志
            // （中断主线程会引入副作用），改用引擎上与线程无关的强制停止标记，
            // 保证主线程上的纯 JS 死循环也能被打断
            val engine = (cx as? AutoJsContext)?.rhinoJavaScriptEngine
            if (engine != null && engine.isForceStopRequested) {
                throw ScriptInterruptedException()
            }
            return
        }
        if (Thread.currentThread().isInterrupted) {
            throw ScriptInterruptedException()
        }
    }

    override fun makeContext(): Context {
        val cx: Context = AutoJsContext(this)
        setupContext(cx)
        return cx
    }

    private fun setupContext(context: Context) {
        context.optimizationLevel = -1
        context.instructionObserverThreshold = 10000
        // 显式开启指令计数回调，保证纯 JS 死循环（如 while(true){}）也能通过
        // observeInstructionCount 检测到中断标志并被 ScriptInterruptedException 打断
        context.setGenerateObserverCount(true)
        context.languageVersion = Context.VERSION_ES6
        context.locale = Locale.getDefault()
        context.wrapFactory = wrapFactory
    }

    override fun onContextCreated(cx: Context) {
        super.onContextCreated(cx)
        val i = mContextCount.incrementAndGet()
        Log.d(LOG_TAG, "onContextCreated: count = $i")
    }

    override fun onContextReleased(cx: Context) {
        super.onContextReleased(cx)
        val i = mContextCount.decrementAndGet()
        Log.d(LOG_TAG, "onContextReleased: count = $i")
    }

    open class WrapFactory : org.mozilla.javascript.WrapFactory() {
        override fun wrap(cx: Context, scope: Scriptable, obj: Any?, staticType: Class<*>?): Any? {
            return when (obj) {
                is UiObject -> UiObjectProxy(obj)
                is String -> bridges.toString(obj)
                is UiObjectCollection -> bridges.asArray(obj)
                else -> super.wrap(cx, scope, obj, staticType)
            }
        }
    }
}