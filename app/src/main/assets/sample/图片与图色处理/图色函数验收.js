// 图色函数验收脚本
// 只读屏幕、不修改任何东西；看控制台的 PASS/FAIL 与耗时数字
// 覆盖：颜色字节序、新增图色 API、掩码法/逐点路径性能、keepCapture 收益、原 API 回归

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
function ms(fn) {
    var t = java.lang.System.nanoTime();
    var r = fn();
    return { r: r, ms: (java.lang.System.nanoTime() - t) / 1e6 };
}
function hex2(v) {
    var s = (v & 0xFFFFFF).toString(16).toUpperCase();
    while (s.length < 6) s = "0" + s;
    return s;
}
// RRGGBB 整数 -> 懒人精灵风格 BBGGRR 字符串
function bgr(v) {
    return hex2(((v & 0xFF) << 16) | (v & 0xFF00) | ((v >> 16) & 0xFF));
}

log("=== 0. 准备 ===");
requestScreenCapture();
sleep(1200);
var img = captureScreen();
var W = img.width, H = img.height;
log("截图尺寸: " + W + " x " + H);

// 找一个 R 与 B 差异明显的像素（灰色无法判别字节序）
var px = -1, py = -1, pc = 0, found = false;
for (var y = 0; y < H && !found; y += 7) {
    for (var x = 0; x < W && !found; x += 7) {
        var c = images.pixel(img, x, y);
        if (Math.abs(((c >> 16) & 0xFF) - (c & 0xFF)) > 60) {
            px = x;
            py = y;
            pc = c;
            found = true;
        }
    }
}
check("找到 R 与 B 差异明显的采样点(判别字节序用)", found, "(" + px + "," + py + ") = #" + hex2(pc));
if (!found) {
    log("屏幕缺少彩色像素，换一个有色界面重跑");
    exit();
}

var lrStr = bgr(pc);           // BBGGRR，懒人精灵风格
var autoStr = "#" + hex2(pc);  // #RRGGBB，AutoX 风格

log("=== 1. 字节序与定位 ===");
var r1 = ms(function () {
    return images.findMultiColorsEx(img, lrStr, [[0, 0, lrStr]], { region: [px, py, 1, 1], threshold: 0 });
});
check("裸6位(BBGGRR)精确命中采样点",
    r1.r != null && Math.abs(r1.r.x - px) <= 1 && Math.abs(r1.r.y - py) <= 1,
    "返回=" + (r1.r ? r1.r.x + "," + r1.r.y : "null") + "  " + r1.ms.toFixed(2) + "ms");

var r2 = images.findMultiColorsEx(img, autoStr, [[0, 0, autoStr]], { region: [px, py, 1, 1], threshold: 0 });
check("带#(RRGGBB)精确命中采样点", r2 != null, "返回=" + (r2 ? r2.x + "," + r2.y : "null"));

var wrong = images.findMultiColorsEx(img, hex2(pc), [[0, 0, hex2(pc)]], { region: [px, py, 1, 1], threshold: 0 });
check("把 RRGGBB 当 BBGGRR 解析会失配(证明字节序生效)", wrong == null,
    "返回=" + (wrong ? wrong.x + "," + wrong.y : "null"));

var rej = images.findMultiColorsEx(img, lrStr, [[0, 0, lrStr], [1, 0, "#123456"]],
    { region: [px, py, 1, 1], threshold: 0 });
check("偏移点不匹配时正确拒绝", rej == null, "返回=" + (rej ? rej.x + "," + rej.y : "null"));

log("=== 2. 新增 API 功能 ===");
var cnt = images.getColorNum(img, "#FFFFFF", { region: [0, 0, 100, 100], threshold: 255 });
check("images.getColorNum(threshold=255) = 100x100", cnt == 10000, "实际=" + cnt);

var cnt2 = getColorNum(0, 0, 100, 100, "FFFFFF", 1.0);
check("全局 getColorNum(x1,y1,x2,y2,color,sim) 可用", typeof cnt2 == "number", "实际=" + cnt2);

var ok1 = cmpColorEx(px + " " + py + " " + lrStr, 0.9);
check("cmpColorEx 命中采样点", ok1 === true, "返回=" + ok1);
var ok2 = cmpColorEx(px + " " + py + " " + hex2(pc), 0.9);
check("cmpColorEx 用错字节序应不命中", ok2 === false, "返回=" + ok2);

var all = images.findAllColors(img, "#FFFFFF", { region: [0, 0, 20, 20], threshold: 255, limit: 5 });
check("findAllColors + limit 生效", all.length == 5, "返回个数=" + all.length);

var tRes = findMultiColorT({
    x1: 0, y1: 0, x2: W, y2: H,
    firstColor: "FFFFFF", sim: 1.0, offsetColor: "0|0|FFFFFF", dir: 0
});
check("findMultiColorT 对象形式可用", tRes != null, "返回=" + (tRes ? tRes.x + "," + tRes.y : "null"));

log("=== 3. 性能：首色命中数 -> 路径与耗时 ===");
// 极端构造：threshold 255 让全屏每个像素都成为候选，即"纯色背景"最坏情况
var bigPaths = [[0, 0, "FFFFFF-FFFFFF"], [10, 0, "FFFFFF-FFFFFF"]];
var nFull = images.getColorNum(img, "#FFFFFF", { region: [0, 0, W, H], threshold: 255 });
var pFull = ms(function () {
    return images.findMultiColorsEx(img, "#FFFFFF", bigPaths, { region: [0, 0, W, H], threshold: 255 });
});
log("  全屏命中数=" + nFull + " -> 掩码法 " + pFull.ms.toFixed(1) + "ms，命中=" + (pFull.r ? pFull.r.x + "," + pFull.r.y : "null"));
check("大命中量(>=5000)下耗时 < 100ms", pFull.ms < 100, pFull.ms.toFixed(1) + "ms");

var pSmall = ms(function () {
    return images.findMultiColorsEx(img, "#FFFFFF", bigPaths, { region: [0, 0, 40, 40], threshold: 255 });
});
log("  40x40 区域 -> 逐点路径 " + pSmall.ms.toFixed(1) + "ms");
check("逐点路径耗时 < 50ms", pSmall.ms < 50, pSmall.ms.toFixed(1) + "ms");

log("=== 4. keepCapture 收益 ===");
var N = 30;
var t0 = ms(function () {
    for (var i = 0; i < N; i++) findMultiColor(0, 0, 60, 60, "FFFFFF", "0|0|FFFFFF", 0, 1.0);
    return null;
});
keepCapture();
var t1 = ms(function () {
    for (var i = 0; i < N; i++) findMultiColor(0, 0, 60, 60, "FFFFFF", "0|0|FFFFFF", 0, 1.0);
    return null;
});
releaseCapture();
log("  " + N + " 次查色：无 keepCapture=" + t0.ms.toFixed(0) + "ms，有 keepCapture=" + t1.ms.toFixed(0) + "ms");
check("keepCapture 后总耗时更短", t1.ms < t0.ms, "提升约 " + (t0.ms / Math.max(t1.ms, 1)).toFixed(1) + "x");

log("=== 5. 回归：原 API 未被破坏 ===");
var g1 = images.findColor(img, autoStr, { region: [px, py, 1, 1], threshold: 0 });
check("images.findColor(img,color,options) 可用", g1 != null);

var g2 = images.findMultiColors(img, autoStr, [[0, 0, pc & 0xFFFFFF]], { region: [px, py, 1, 1], threshold: 0 });
check("images.findMultiColors 原签名可用", g2 != null);

var g3 = findColor(img, autoStr, { region: [px, py, 1, 1], threshold: 0 });
check("全局 findColor 传图(AutoX 重载)可用", g3 != null);

var g4 = findColor(px, py, px + 2, py + 2, lrStr, 0, 1.0);
check("全局 findColor(x1,y1,x2,y2,color,dir,sim) 可用", g4 != null, "返回=" + (g4 ? g4.x + "," + g4.y : "null"));

img.recycle();
log("=== 汇总: PASS=" + pass + "  FAIL=" + fail + " ===");
