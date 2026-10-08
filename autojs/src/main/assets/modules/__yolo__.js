module.exports = function (runtime, global) {
    const yoloApi = new com.stardust.autojs.runtime.api.Yolo(runtime);

    const yolo = {};

    /**
     * 目标检测（TFLite，模型自备）.
     *
     * yolo.detect(modelPath, options)             // 自动截屏
     * yolo.detect(image, modelPath, options)      // 检测指定图片
     *
     * options: { labels, confThreshold, iouThreshold, numThreads }
     * 返回: [{ label, confidence, bounds: {left, top, right, bottom, centerX, centerY}, centerX, centerY }]
     */
    yolo.detect = function (imageOrPath, maybePath, maybeOptions) {
        let image = null;
        let modelPath = null;
        let options = {};
        if (typeof imageOrPath === 'string') {
            modelPath = imageOrPath;
            if (maybePath && typeof maybePath === 'object') {
                options = maybePath;
            }
        } else {
            if (imageOrPath) {
                image = imageOrPath;
            }
            modelPath = maybePath;
            if (maybeOptions && typeof maybeOptions === 'object') {
                options = maybeOptions;
            }
        }
        if (!modelPath) {
            throw new Error("yolo.detect: 缺少 modelPath（.tflite 文件路径）");
        }
        let labels = options.labels;
        if (Array.isArray(labels)) {
            labels = labels.join(",");
        }
        const result = yoloApi.detect(
            image,
            modelPath,
            labels || null,
            typeof options.confThreshold === "number" ? options.confThreshold : 0.25,
            typeof options.iouThreshold === "number" ? options.iouThreshold : 0.45,
            typeof options.numThreads === "number" ? options.numThreads : 4
        );
        return global.util.java.toJsArray(result).map(function (d) {
            const b = d.bounds;
            const cx = b.centerX();
            const cy = b.centerY();
            return {
                label: d.label,
                confidence: d.confidence,
                bounds: {
                    left: b.left,
                    top: b.top,
                    right: b.right,
                    bottom: b.bottom,
                    centerX: cx,
                    centerY: cy
                },
                centerX: cx,
                centerY: cy
            };
        });
    };

    return yolo;
};
