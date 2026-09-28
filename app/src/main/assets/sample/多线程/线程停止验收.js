// 线程停止验收脚本
// 自动判定 3 个子线程场景（忙循环 / 空闲阻塞 / sleep 循环），isAlive() 直接给结论
// 另有 3 个人工观察用例写在文件末尾，需单独跑

var pass = 0, fail = 0;
function check(name, ok, extra) {
    if (ok) {
        pass++;
        log("[PASS] " + name + (extra ? "   " + extra : ""));
    } else {
        fail++;
        log("[FAIL] " + name + (extra ? "   " + extra : ""));
    }
}

log("=== 1. 忙循环子线程（纯 JS 死循环，靠指令计数中断打断）===");
var t1 = threads.start(function () {
    while (true) {
    }
});
sleep(1500);
t1.interrupt();
sleep(2500);
check("忙循环子线程已退出", !t1.isAlive(), "isAlive=" + t1.isAlive());

log("=== 2. 已跑完函数的空闲子线程（阻塞在 Looper/nativePollOnce 的场景）===");
var t2 = threads.start(function () {
    // 函数立即返回，线程随后空闲阻塞在 Looper.loop()
});
sleep(800);
t2.interrupt();
sleep(2500);
check("空闲子线程已退出", !t2.isAlive(), "isAlive=" + t2.isAlive());

log("=== 3. sleep 循环子线程（靠 InterruptedException 退出）===");
var t3 = threads.start(function () {
    while (true) {
        sleep(1000);
    }
});
sleep(1500);
t3.interrupt();
sleep(2500);
check("sleep 循环子线程已退出", !t3.isAlive(), "isAlive=" + t3.isAlive());

log("=== 4. 线程登记表不应残留 ===");
check("无残留子线程", !threads.hasRunningThreads());

log("=== 汇总: PASS=" + pass + "  FAIL=" + fail + " ===");

// =====================================================================
// 以下为需要单独运行 + 人工观察的用例（本脚本无法自动判定）
//
// A. 脚本主线程忙循环 -> 点浮动窗"停止"
//      while (true) { }
//    期望：点击后立即停住，控制台出现"任务结束"，不需要杀进程
//
// B. UI 模式忙循环 -> 点"停止"（验证不 ANR）
//      "ui";
//      while (true) { }
//    期望：能停住、界面不卡死（修复前这里会永久 ANR，因为脚本跑在 Android 主线程上）
//
// C. 脚本自然结束后子线程不应继续跑
//      让子线程每 500ms toastLog 一次（如 多线程/线程启动与关闭.js 那样），
//      主流程不 interrupt，直接让脚本结束
//    期望：脚本结束后 toastLog 停止输出（修复前空闲子线程会一直存活）
// =====================================================================
