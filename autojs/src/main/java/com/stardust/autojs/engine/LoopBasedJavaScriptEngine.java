package com.stardust.autojs.engine;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.autojs.script.ScriptSource;

import org.mozilla.javascript.ContinuationPending;

/**
 * Created by Stardust on 2017/7/28.
 */

public class LoopBasedJavaScriptEngine extends RhinoJavaScriptEngine {

    public interface ExecuteCallback {
        void onResult(Object r);

        void onException(Exception e);
    }

    private Handler mHandler;
    private boolean mLooping = false;

    public LoopBasedJavaScriptEngine(Context context) {
        super(context);
    }

    @Override
    public Object execute(final JavaScriptSource source) {
        execute(source, null);
        return null;
    }


    public void execute(final ScriptSource source, final ExecuteCallback callback) {
        Runnable r = () -> {
            try {
                Object o = LoopBasedJavaScriptEngine.super.execute((JavaScriptSource) source);
                if (callback != null)
                    callback.onResult(o);
            } catch (ContinuationPending ignored) {
            } catch (Exception e) {
                if (callback == null) {
                    throw e;
                } else {
                    callback.onException(e);
                }
            }


        };
        mHandler.post(r);
        if (!mLooping && Looper.myLooper() != Looper.getMainLooper()) {
            mLooping = true;
            while (true) {
                try {
                    Looper.loop();
                } catch (ContinuationPending ignored) {
                    continue;
                } catch (Throwable t) {
                    mLooping = false;
                    throw t;
                }
                break;
            }
        }
    }

    @Override
    public void forceStop() {
        getRuntime().loopers.forceStop();
        Activity activity = (Activity) getTag("activity");
        if (activity != null) {
            activity.finish();
        }
        super.forceStop();
        // 仅调用 Thread.interrupt() 无法唤醒阻塞在 nativePollOnce 的脚本主线程 looper，
        // 必须额外投递一条消息，否则脚本主线程在空闲时无法感知中断、停不下来
        ScriptRuntime runtime = getRuntime();
        if (runtime != null && runtime.loopers != null) {
            runtime.loopers.wakeUp();
        }
    }

    @Override
    public synchronized void destroy() {
        getRuntime().loopers.forceStop();
        super.destroy();
    }

    @Override
    public void init() {
        if (Looper.myLooper() == null) Looper.prepare();
        mHandler = new Handler();
        super.init();
    }


}
