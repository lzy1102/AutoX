package org.autojs.autojs.mcp.tools

import org.autojs.autojs.mcp.McpRegistry

/**
 * C1 工具集注册.
 *
 * 域划分：device / shell / screen / ocr / ui / input / app.
 * 新增工具只需实现 [McpTool] 并在此注册，保持 tools/list 顺序稳定.
 */
fun McpRegistry.registerC1Tools() {
    registerAll(
        DeviceStatusTool(),
        ShellTool(),
        CaptureTool(),
        OcrTool(),
        UiDumpTool(),
        UiFindTool(),
        UiClickTool(),
        UiInputTool(),
        InputTapTool(),
        InputLongClickTool(),
        InputSwipeTool(),
        InputGestureTool(),
        InputKeyTool(),
        AppLaunchTool(),
        AppCurrentTool(),
        AppOpenUrlTool(),
    )
}

/** C2 图像工具：找图找色. */
fun McpRegistry.registerImageTools() {
    registerAll(
        FindImageTool(),
        FindAllImagesTool(),
        FindColorTool(),
        FindAllColorsTool(),
        FindMultiColorsTool(),
        CmpColorsTool(),
        YoloDetectTool(),
    )
}

/** C3 工具：脚本 / 剪贴板 / 应用列表. */
fun McpRegistry.registerC3Tools() {
    registerAll(
        ScriptRunTool(),
        ScriptStopTool(),
        ScriptListTool(),
        ClipboardGetTool(),
        ClipboardSetTool(),
        AppListTool(),
    )
}
