package com.stardust.autojs.core.looper

import android.os.Looper
import androidx.annotation.CallSuper
import com.stardust.autojs.engine.RhinoJavaScriptEngine
import com.stardust.autojs.runtime.ScriptRuntime
import com.stardust.autojs.runtime.exception.ScriptInterruptedException
import com.stardust.lang.ThreadCompat
import org.mozilla.javascript.Context

/**
 * Created by Stardust on 2017/12/27.
 */
open class TimerThread(private val mRuntime: ScriptRuntime, private val mTarget: Runnable) :
    ThreadCompat(mTarget) {
    private var mTimer: Timer? = null
    private var mRunning = false
    private val mRunningLock = java.lang.Object()
    private val mAsyncTask = Loopers.AsyncTask("TimerThread")
    var loopers: Loopers? = null

    init {
        mRuntime.loopers.addAsyncTask(mAsyncTask)
    }

    /**
     * [Thread.interrupt] 只能设置中断标志，无法唤醒阻塞在 Looper.loop() 中
     * nativePollOnce 的线程。这里在设置中断标志后额外唤醒本线程的 looper，
     * 使其能感知中断并通过 IdleHandler 退出循环，避免线程杀不死。
     */
    override fun interrupt() {
        super.interrupt()
        loopers?.wakeUp()
    }

    override fun run() {
        loopers = Loopers(mRuntime)
        mTimer = loopers!!.mTimer
        (mRuntime.engines.myEngine() as RhinoJavaScriptEngine).enterContext()
        notifyRunning()
        if (isInterrupted) {
            // 启动前就已被要求中断：不执行目标函数，直接唤醒 looper 让其退出
            loopers!!.wakeUp()
        } else {
            mTimer!!.post(mTarget)
        }
        try {
            Looper.loop()
        } catch (e: Throwable) {
            if (!ScriptInterruptedException.causedByInterrupted(e)) {
                mRuntime.console.error(currentThread().toString() + ": ", e)
            }
        } finally {
            //mRuntime.console.log("TimerThread exit");
            onExit()
            mTimer = null
            Context.exit()
        }
    }


    private fun notifyRunning() {
        synchronized(mRunningLock) {
            mRunning = true
            mRunningLock.notifyAll()
        }
    }

    @CallSuper
    protected open fun onExit() {
        mRuntime.loopers.removeAsyncTask(mAsyncTask)
        mRuntime.loopers.notifyThreadExit(this)
    }

    fun setTimeout(vararg args: Any?): Int {
        val listener = args.elementAtOrNull(0)
        check(listener != null) { "callback cannot be null" }
        val delay = (args.elementAtOrNull(1) as? Double)?.toLong() ?: 1
        return timer.setTimeout(listener, delay, *args.drop(2).toTypedArray())
    }

    fun setImmediate(vararg args: Any?): Int {
        val listener = args.elementAtOrNull(0)
        check(listener != null) { "callback cannot be null" }
        return timer.setImmediate(listener, *args.drop(1).toTypedArray())
    }

    fun setInterval(vararg args: Any?): Int {
        val listener = args.elementAtOrNull(0)
        check(listener != null) { "callback cannot be null" }
        val delay = (args.elementAtOrNull(1) as? Double)?.toLong() ?: 1
        return timer.setInterval(listener, delay, *args.drop(2).toTypedArray())
    }

    val timer: Timer
        get() {
            checkNotNull(mTimer) { "thread is not alive" }
            return mTimer as Timer
        }

    fun clearTimeout(id: Int): Boolean {
        return timer.clearTimeout(id)
    }

    fun clearInterval(id: Int): Boolean {
        return timer.clearInterval(id)
    }

    fun clearImmediate(id: Int): Boolean {
        return timer.clearImmediate(id)
    }

    @Throws(InterruptedException::class)
    fun waitFor() {
        synchronized(mRunningLock) {
            if (mRunning) return
            mRunningLock.wait()
        }
    }

    override fun toString(): String {
        return "Thread[$name,$priority]"
    }
}