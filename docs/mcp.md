# AutoX MCP Server

AutoX 内置的 MCP（Model Context Protocol）Server，让 AI 客户端（Claude Code、Cursor、Trae、MCP Inspector 等）直连手机执行自动化：截图、找图找色、OCR、YOLO 目标检测、点击滑动、控件操作、应用与 shell 管理。

- 协议：Streamable HTTP（`2026-07-28`），兼容旧 `initialize` 握手（`2025-03-26` ~ `2024-11-05`）
- 端点：`POST http://127.0.0.1:9318/mcp`（默认）
- 引擎：独立 Netty 实例，独立端口，与 VS Code 调试通道（9317）完全隔离

## 开启与设置

| 入口 | 项 | 默认 |
|---|---|---|
| 主界面抽屉 | MCP 服务开关 | 关 |
| 设置 → MCP 服务 | 端口 | `9318` |
| | 允许局域网访问 | 关（仅 `127.0.0.1`） |
| | 需要鉴权（Token） | 开 |
| | 审计日志 | 开 |

改端口/局域网开关时若服务在运行，会自动重启并提示新地址。**局域网模式强制要求鉴权**，否则拒绝启动。

## 连接方式

**推荐：USB + adb forward（不暴露网络、无需局域网）**

```bash
adb forward tcp:9318 tcp:9318
```

客户端配置 `http://127.0.0.1:9318/mcp` 即可。

**局域网**：打开「允许局域网访问」后，用手机 IP 连接（如 `http://192.168.1.23:9318/mcp`）。无 TLS，Token 为明文传输，仅建议可信网络使用。

### 客户端配置示例

```json
{
  "mcpServers": {
    "autox": {
      "url": "http://127.0.0.1:9318/mcp",
      "headers": {
        "Authorization": "Bearer <token>"
      }
    }
  }
}
```

> 不同客户端字段不同（`type: "http"` / `"streamable-http"` 等），以各自文档为准。无 Token 需求时可关闭「需要鉴权」并去掉 headers。

**获取 Token**：设置 → MCP 服务 → **复制 Token**（未生成时会自动生成并复制到剪贴板，摘要显示脱敏值）；**重新生成 Token** 会让旧值立即失效，记得同步更新客户端配置。只走 `adb forward` 本机连接时也可直接关闭「需要鉴权」。

### curl 验证

```bash
curl -X POST http://127.0.0.1:9318/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"server/discover"}'
```

## 协议行为

- `POST /mcp`：JSON-RPC 2.0；`Content-Type: application/json`；`Accept` 含 `application/json` 或 `*/*`
- 带 `MCP-Protocol-Version` 头时校验版本（与 `_meta` 不一致 → `-32020`；不支持 → `-32022`）；不带头按旧客户端放行
- 通知（无 `id`）→ `202` 空 body
- `GET /mcp` → `405`（2026-07-28 已移除 GET 流端点）
- `GET /sse` → `404` + 诊断信息（不支持 2025-03-26 HTTP+SSE 传输）
- 旧握手：`initialize` 回显版本 + 常量 `Mcp-Session-Id`（从不校验）；`ping` / `logging/setLevel` / `resources/list` / `prompts/list` 均有兼容响应
- 方法：`server/discover`、`tools/list`、`tools/call`
- 安全校验：`Origin` / `Host` 白名单（局域网模式加入本机各网卡 IPv4），Token 走常量时间比较

## 工具参考（23 个）

坐标一律为**设备物理像素**；先用 `device_status` 自检环境。

### 环境

| 工具 | 入参 | 返回 |
|---|---|---|
| `device_status` | — | 无障碍状态/存活、录屏可用性、前台包与 Activity、屏幕尺寸、电量、亮屏、SDK、机型 |

### Shell

| 工具 | 入参 | 返回 |
|---|---|---|
| `shell_exec` | `command`（必填，≤8000 字符）、`root`（默认 false）、`timeoutMs`（默认 15000，上限 60000） | `exitCode` / `stdout` / `stderr` |

### 屏幕与视觉

| 工具 | 关键入参 | 返回 |
|---|---|---|
| `screen_capture` | `maxWidth`（默认 1080，0=不缩放） | 文本 + JPEG 图片，`width/height` 物理像素 |
| `ocr_recognize` | `language`（zh/ja/ko/sa/latin，默认 zh）、`maxWidth` | `fullText` + `lines[{text,bounds,confidence}]` + 图片 |
| `find_image` | `template`（base64，必填）、`image`（缺省现截屏）、`threshold`(0.9)、`weakThreshold`(0.7)、`region` | `found/x/y/similarity` |
| `find_all_images` | 同上 + `limit`（默认 5） | `count` + `matches[{x,y,similarity}]` |
| `find_color` | `color`（`#RRGGBB`/`#AARRGGBB`/`0x...`/十进制）、`tolerance`(4)、`dir`(0~4)、`region`、`image`? | `found/x/y` |
| `find_all_colors` | `colors[]`（≤10）、`tolerance`、`dir`、`limit`(100) | `count` + `points[]` |
| `find_multi_colors` | `firstColor`、`firstTolerance`、`points[{dx,dy,color,tolerance?}]`（≤20）、`dir` | `found/x/y`（首色坐标） |
| `cmp_colors` | `points[{x,y,color,tolerance?}]`（≤20）、`image`? | `match: true/false` |
| `yolo_detect` | `modelPath`（必填，手机上的 `.tflite`）、`confThreshold`(0.25)、`iouThreshold`(0.45)、`labels[]`、`numThreads`(4)、`image`? | `detections[{label,confidence,bounds{...,centerX,centerY}}]` |

`dir`：0=左上→右下，1=中心向外，2=右下→左上，3=左下→右上，4=右上→左下。

### 无障碍控件

选择器条件（至少一个）：`text` / `textContains` / `textMatches` / `desc` / `descContains` / `descMatches` / `id` / `idContains` / `className` / `packageName` / `clickable` / `scrollable` / `editable` / `checkable` / `depth`；`timeoutMs` 默认 3000。

| 工具 | 说明 |
|---|---|
| `ui_dump` | 导出前台窗口控件树（`maxNodes` 默认 120） |
| `ui_find` | 查找控件，返回文本与 bounds（`limit` 默认 10） |
| `ui_click` | 查找并点击首个命中 |
| `ui_input` | 查找输入框并 `setText`（`content`） |

### 触摸与按键

| 工具 | 说明 |
|---|---|
| `input_tap` | 点击 `(x,y)` |
| `input_long_click` | 长按 `(x,y)` |
| `input_swipe` | `(x1,y1)→(x2,y2)`，`durationMs` 默认 500 |
| `input_gesture` | 折线手势 `points[[x,y],...]`（≤20 点）+ `durationMs`（≤10000） |
| `input_key` | `action`：back/home/recents/notifications/quickSettings/splitScreen，或 `keycode` + `code`（走 `input keyevent`） |

### 应用

| 工具 | 说明 |
|---|---|
| `app_launch` | `packageName` 或 `appName` 启动 |
| `app_current` | 当前前台包名/Activity |
| `app_open_url` | `ACTION_VIEW` 打开 url |

## 错误处理

工具业务失败返回 `result.isError: true`，`structuredContent.errorCode` 取值：

`NO_ACCESSIBILITY_SERVICE` / `SCREEN_CAPTURE_NOT_GRANTED` / `NO_ROOT` / `NO_FOREGROUND_ACTIVITY` / `NODE_NOT_FOUND` / `STALE_NODE` / `GESTURE_FAILED` / `TIMEOUT` / `INVALID_IMAGE` / `OCR_NOT_READY` / `UNSUPPORTED_API` / `INVALID_PARAMS` / `INTERNAL`

要点：无障碍未开启时 `ui_*` 与 `input_*` 全局动作不可用；首次截图/OCR 需在手机上确认录屏授权。

## YOLO 模型准备

```bash
pip install ultralytics
yolo export model=best.pt format=tflite   # 默认 fp32，输出 best_float32.tflite
adb push best_float32.tflite /sdcard/models/yolo11n.tflite
```

调用：`yolo_detect` 传 `modelPath=/sdcard/models/yolo11n.tflite`，`labels` 按模型类别顺序填。仅支持 fp32、方形输入（160~1280）；首次加载后按文件路径+修改时间缓存。

## 脚本内使用（JS）

新增全局对象 `yolo`，风格与 `images.*` 一致：

```javascript
if (!images.requestScreenCapture()) { toast("请先给录屏权限"); exit(); }

// 自动截屏检测
var list = yolo.detect("/sdcard/models/yolo11n.tflite", {
    labels: ["怪", "按钮"],
    confThreshold: 0.25
});

// 或检测指定图片
// var list = yolo.detect(images.captureScreen(), "/sdcard/models/yolo11n.tflite", { labels: ["怪"] });

list.forEach(function (d) {
    if (d.label === "怪") click(d.centerX, d.centerY);
});
```

返回项：`{ label, confidence, bounds:{left,top,right,bottom,centerX,centerY}, centerX, centerY }`。

## 已知限制

- 未实现前台服务保活；App 被杀后服务停止
- `script_*`（跑脚本/停脚本）、剪贴板、已安装应用列表等工具待补
- 不支持 2025-03-26 HTTP+SSE 客户端（`/sse` 明确报错）
