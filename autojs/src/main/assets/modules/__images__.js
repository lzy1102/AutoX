
module.exports = function (runtime, scope) {
    const ResultAdapter = require("result_adapter");

    var MatchingResult = (function () {
        var comparators = {
            "left": (l, r) => l.point.x - r.point.x,
            "top": (l, r) => l.point.y - r.point.y,
            "right": (l, r) => r.point.x - l.point.x,
            "bottom": (l, r) => r.point.y - l.point.y
        }
        function MatchingResult(list) {
            if (Array.isArray(list)) {
                this.matches = list;
            } else {
                this.matches = runtime.bridges.toArray(list);
            }
            this.__defineGetter__("points", () => {
                if (typeof (this.__points__) == 'undefined') {
                    this.__points__ = this.matches.map(m => m.point);
                }
                return this.__points__;
            });
        }
        MatchingResult.prototype.first = function () {
            if (this.matches.length == 0) {
                return null;
            }
            return this.matches[0];
        }
        MatchingResult.prototype.last = function () {
            if (this.matches.length == 0) {
                return null;
            }
            return this.matches[this.matches.length - 1];
        }
        MatchingResult.prototype.findMax = function (cmp) {
            if (this.matches.length == 0) {
                return null;
            }
            var target = this.matches[0];
            this.matches.forEach(m => {
                if (cmp(target, m) > 0) {
                    target = m;
                }
            });
            return target;
        }
        MatchingResult.prototype.leftmost = function () {
            return this.findMax(comparators.left);
        }
        MatchingResult.prototype.topmost = function () {
            return this.findMax(comparators.top);
        }
        MatchingResult.prototype.rightmost = function () {
            return this.findMax(comparators.right);
        }
        MatchingResult.prototype.bottommost = function () {
            return this.findMax(comparators.bottom);
        }
        MatchingResult.prototype.worst = function () {
            return this.findMax((l, r) => l.similarity - r.similarity);
        }
        MatchingResult.prototype.best = function () {
            return this.findMax((l, r) => r.similarity - l.similarity);
        }
        MatchingResult.prototype.sortBy = function (cmp) {
            var comparatorFn = null;
            if (typeof (cmp) == 'string') {
                cmp.split("-").forEach(direction => {
                    var buildInFn = comparators[direction];
                    if (!buildInFn) {
                        throw new Error("unknown direction '" + direction + "' in '" + cmp + "'");
                    }
                    (function (fn) {
                        if (comparatorFn == null) {
                            comparatorFn = fn;
                        } else {
                            comparatorFn = (function (comparatorFn, fn) {
                                return function (l, r) {
                                    var cmpValue = comparatorFn(l, r);
                                    if (cmpValue == 0) {
                                        return fn(l, r);
                                    }
                                    return cmpValue;
                                }
                            })(comparatorFn, fn);
                        }
                    })(buildInFn);
                });
            } else {
                comparatorFn = cmp;
            }
            var clone = this.matches.slice();
            clone.sort(comparatorFn);
            return new MatchingResult(clone);
        }
        return MatchingResult;
    })();

    function images() {
    }
    if (android.os.Build.VERSION.SDK_INT >= 21) {
        util.__assignFunctions__(runtime.images, images, ['captureScreen', 'read', 'copy', 'load', 'clip', 'pixel'])
    }
    images.opencvImporter = JavaImporter(
        org.opencv.core.Point,
        org.opencv.core.Point3,
        org.opencv.core.Rect,
        org.opencv.core.Algorithm,
        org.opencv.core.Scalar,
        org.opencv.core.Size,
        org.opencv.core.Core,
        org.opencv.core.CvException,
        org.opencv.core.CvType,
        org.opencv.core.TermCriteria,
        org.opencv.core.RotatedRect,
        org.opencv.core.Range,
        org.opencv.imgproc.Imgproc,
        com.stardust.autojs.core.opencv
    );
    with (images.opencvImporter) {
        const defaultColorThreshold = 4;

        var colors = Object.create(runtime.colors);
        colors.alpha = function (color) {
            color = parseColor(color);
            return color >>> 24;
        }
        colors.red = function (color) {
            color = parseColor(color);
            return (color >> 16) & 0xFF;
        }
        colors.green = function (color) {
            color = parseColor(color);
            return (color >> 8) & 0xFF;
        }
        colors.blue = function (color) {
            color = parseColor(color);
            return color & 0xFF;
        }

        colors.isSimilar = function (c1, c2, threshold, algorithm) {
            c1 = parseColor(c1);
            c2 = parseColor(c2);
            threshold = threshold == undefined ? 4 : threshold;
            algorithm = algorithm == undefined ? "diff" : algorithm;
            var colorDetector = getColorDetector(c1, algorithm, threshold);
            return colorDetector.detectsColor(colors.red(c2), colors.green(c2), colors.blue(c2));
        }

        var javaImages = runtime.getImages();

        var colorFinder = javaImages.colorFinder;

        images.requestScreenCapture = function (landscape) {
            let ScreenCapturer = com.stardust.autojs.core.image.capture.ScreenCapturer;
            var orientation = ScreenCapturer.ORIENTATION_AUTO;
            if (landscape === true) {
                orientation = ScreenCapturer.ORIENTATION_LANDSCAPE;
            }
            if (landscape === false) {
                orientation = ScreenCapturer.ORIENTATION_PORTRAIT;
            }
            return javaImages.requestScreenCapture(orientation);
        }

        images.save = function (img, path, format, quality) {
            format = format || "png";
            quality = quality == undefined ? 100 : quality;
            return javaImages.save(img, path, format, quality);
        }
        images.stopScreenCapturer = javaImages.stopScreenCapturer.bind(javaImages)
        images.saveImage = images.save;

        images.grayscale = function (img, dstCn) {
            return images.cvtColor(img, "BGR2GRAY", dstCn);
        }

        images.threshold = function (img, threshold, maxVal, type) {
            initIfNeeded();
            var mat = new Mat();
            type = type || "BINARY";
            type = Imgproc["THRESH_" + type];
            Imgproc.threshold(img.mat, mat, threshold, maxVal, type);
            return images.matToImage(mat);
        }

        images.inRange = function (img, lowerBound, upperBound) {
            initIfNeeded();
            var lb = new Scalar(colors.red(lowerBound), colors.green(lowerBound),
                colors.blue(lowerBound), colors.alpha(lowerBound));
            var ub = new Scalar(colors.red(upperBound), colors.green(upperBound),
                colors.blue(upperBound), colors.alpha(lowerBound))
            var bi = new Mat();
            Core.inRange(img.mat, lb, ub, bi);
            return images.matToImage(bi);
        }

        images.interval = function (img, color, threshold) {
            initIfNeeded();
            var lb = new Scalar(colors.red(color) - threshold, colors.green(color) - threshold,
                colors.blue(color) - threshold, colors.alpha(color));
            var ub = new Scalar(colors.red(color) + threshold, colors.green(color) + threshold,
                colors.blue(color) + threshold, colors.alpha(color));
            var bi = new Mat();
            Core.inRange(img.mat, lb, ub, bi);
            return images.matToImage(bi);
        }

        images.adaptiveThreshold = function (img, maxValue, adaptiveMethod, thresholdType, blockSize, C) {
            initIfNeeded();
            var mat = new Mat();
            adaptiveMethod = Imgproc["ADAPTIVE_THRESH_" + adaptiveMethod];
            thresholdType = Imgproc["THRESH_" + thresholdType];
            Imgproc.adaptiveThreshold(img.mat, mat, maxValue, adaptiveMethod, thresholdType, blockSize, C);
            return images.matToImage(mat);

        }
        images.blur = function (img, size, point, type) {
            initIfNeeded();
            var mat = new Mat();
            size = newSize(size);
            type = Core["BORDER_" + (type || "DEFAULT")];
            if (point == undefined) {
                Imgproc.blur(img.mat, mat, size);
            } else {
                Imgproc.blur(img.mat, mat, size, new Point(point[0], point[1]), type);
            }
            return images.matToImage(mat);
        }

        images.medianBlur = function (img, size) {
            initIfNeeded();
            var mat = new Mat();
            Imgproc.medianBlur(img.mat, mat, size);
            return images.matToImage(mat);
        }


        images.gaussianBlur = function (img, size, sigmaX, sigmaY, type) {
            initIfNeeded();
            var mat = new Mat();
            size = newSize(size);
            sigmaX = sigmaX == undefined ? 0 : sigmaX;
            sigmaY = sigmaY == undefined ? 0 : sigmaY;
            type = Core["BORDER_" + (type || "DEFAULT")];
            Imgproc.GaussianBlur(img.mat, mat, size, sigmaX, sigmaY, type);
            return images.matToImage(mat);
        }

        images.cvtColor = function (img, code, dstCn) {
            initIfNeeded();
            var mat = new Mat();
            code = Imgproc["COLOR_" + code];
            if (dstCn == undefined) {
                Imgproc.cvtColor(img.mat, mat, code);
            } else {
                Imgproc.cvtColor(img.mat, mat, code, dstCn);
            }
            return images.matToImage(mat);
        }

        images.findCircles = function (grayImg, options) {
            initIfNeeded();
            options = options || {};
            var mat = options.region == undefined ? grayImg.mat : new Mat(grayImg.mat, buildRegion(options.region, grayImg));
            var resultMat = new Mat()
            var dp = options.dp == undefined ? 1 : options.dp;
            var minDst = options.minDst == undefined ? grayImg.height / 8 : options.minDst;
            var param1 = options.param1 == undefined ? 100 : options.param1;
            var param2 = options.param2 == undefined ? 100 : options.param2;
            var minRadius = options.minRadius == undefined ? 0 : options.minRadius;
            var maxRadius = options.maxRadius == undefined ? 0 : options.maxRadius;
            Imgproc.HoughCircles(mat, resultMat, Imgproc.CV_HOUGH_GRADIENT, dp, minDst, param1, param2, minRadius, maxRadius);
            var result = [];
            for (var i = 0; i < resultMat.rows(); i++) {
                for (var j = 0; j < resultMat.cols(); j++) {
                    var d = resultMat.get(i, j);
                    result.push({
                        x: d[0],
                        y: d[1],
                        radius: d[2]
                    });
                }
            }
            if (options.region != undefined) {
                mat.release();
            }
            resultMat.release();
            return result;
        }

        images.resize = function (img, size, interpolation) {
            initIfNeeded();
            var mat = new Mat();
            interpolation = Imgproc["INTER_" + (interpolation || "LINEAR")];
            Imgproc.resize(img.mat, mat, newSize(size), 0, 0, interpolation);
            return images.matToImage(mat);
        }

        images.scale = function (img, fx, fy, interpolation) {
            initIfNeeded();
            var mat = new Mat();
            interpolation = Imgproc["INTER_" + (interpolation || "LINEAR")];
            Imgproc.resize(img.mat, mat, newSize([0, 0]), fx, fy, interpolation);
            return images.matToImage(mat);
        }

        images.rotate = function (img, degree, x, y) {
            initIfNeeded();
            if (x == undefined) {
                x = img.width / 2;
            }
            if (y == undefined) {
                y = img.height / 2;
            }
            return javaImages.rotate(img, x, y, degree);
        }

        images.concat = function (img1, img2, direction) {
            initIfNeeded();
            direction = direction || "right";
            return javaImages.concat(img1, img2, android.view.Gravity[direction.toUpperCase()]);
        }

        images.detectsColor = function (img, color, x, y, threshold, algorithm) {
            initIfNeeded();
            color = parseColor(color);
            algorithm = algorithm || "diff";
            threshold = threshold || defaultColorThreshold;
            var colorDetector = getColorDetector(color, algorithm, threshold);
            var pixel = images.pixel(img, x, y);
            return colorDetector.detectsColor(colors.red(pixel), colors.green(pixel), colors.blue(pixel));
        }

        images.findColor = function (img, color, options) {
            initIfNeeded();
            color = parseColor(color);
            options = options || {};
            var region = options.region || [];
            if (options.similarity) {
                var threshold = parseInt(255 * (1 - options.similarity));
            } else {
                var threshold = options.threshold || defaultColorThreshold;
            }
            if (options.region) {
                return colorFinder.findColor(img, color, threshold, buildRegion(options.region, img));
            } else {
                return colorFinder.findColor(img, color, threshold, null);
            }
        }

        images.findColorInRegion = function (img, color, x, y, width, height, threshold) {
            return findColor(img, color, {
                region: [x, y, width, height],
                threshold: threshold
            });
        }

        images.findColorEquals = function (img, color, x, y, width, height) {
            return findColor(img, color, {
                region: [x, y, width, height],
                threshold: 0
            });
        }

        images.findAllPointsForColor = function (img, color, options) {
            initIfNeeded();
            color = parseColor(color);
            options = options || {};
            if (options.similarity) {
                var threshold = parseInt(255 * (1 - options.similarity));
            } else {
                var threshold = options.threshold || defaultColorThreshold;
            }
            if (options.region) {
                return toPointArray(colorFinder.findAllPointsForColor(img, color, threshold, buildRegion(options.region, img)));
            } else {
                return toPointArray(colorFinder.findAllPointsForColor(img, color, threshold, null));
            }
        }

        images.findMultiColors = function (img, firstColor, paths, options) {
            initIfNeeded();
            options = options || {};
            firstColor = parseColor(firstColor);
            var list = java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, paths.length * 3);
            for (var i = 0; i < paths.length; i++) {
                var p = paths[i];
                list[i * 3] = p[0];
                list[i * 3 + 1] = p[1];
                list[i * 3 + 2] = parseColor(p[2]);
            }
            var region = options.region ? buildRegion(options.region, img) : null;
            var threshold = options.threshold === undefined ? defaultColorThreshold : options.threshold;
            return colorFinder.findMultiColors(img, firstColor, threshold, region, list);
        }

        images.findImage = function (img, template, options) {
            initIfNeeded();
            options = options || {};
            var threshold = options.threshold || 0.9;
            var maxLevel = -1;
            if (typeof (options.level) == 'number') {
                maxLevel = options.level;
            }
            var weakThreshold = options.weakThreshold || 0.6;
            if (options.region) {
                return javaImages.findImage(img, template, weakThreshold, threshold, buildRegion(options.region, img), maxLevel);
            } else {
                return javaImages.findImage(img, template, weakThreshold, threshold, null, maxLevel);
            }
        }

        images.matchTemplate = function (img, template, options) {
            initIfNeeded();
            options = options || {};
            var threshold = options.threshold || 0.9;
            var maxLevel = -1;
            if (typeof (options.level) == 'number') {
                maxLevel = options.level;
            }
            var max = options.max || 5;
            var weakThreshold = options.weakThreshold || 0.6;
            var result;
            if (options.region) {
                result = javaImages.matchTemplate(img, template, weakThreshold, threshold, buildRegion(options.region, img), maxLevel, max);
            } else {
                result = javaImages.matchTemplate(img, template, weakThreshold, threshold, null, maxLevel, max);
            }
            return new MatchingResult(result);
        }



        images.findImageInRegion = function (img, template, x, y, width, height, threshold) {
            return images.findImage(img, template, {
                region: [x, y, width, height],
                threshold: threshold
            });
        }

        images.fromBase64 = function (base64) {
            return javaImages.fromBase64(base64);
        }

        images.toBase64 = function (img, format, quality) {
            format = format || "png";
            quality = quality == undefined ? 100 : quality;
            return javaImages.toBase64(img, format, quality);
        }

        images.fromBytes = function (bytes) {
            return javaImages.fromBytes(bytes);
        }

        images.toBytes = function (img, format, quality) {
            format = format || "png";
            quality = quality == undefined ? 100 : quality;
            return javaImages.toBytes(img, format, quality);
        }

        images.readPixels = function (path) {
            var img = images.read(path);
            var bitmap = img.getBitmap();
            var w = bitmap.getWidth();
            var h = bitmap.getHeight();
            var pixels = util.java.array("int", w * h);
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            img.recycle();
            return {
                data: pixels,
                width: w,
                height: h
            };
        }

        images.matToImage = function (img) {
            initIfNeeded();
            return Image.ofMat(img);
        }





        function getColorDetector(color, algorithm, threshold) {
            switch (algorithm) {
                case "rgb":
                    return new com.stardust.autojs.core.image.ColorDetector.RGBDistanceDetector(color, threshold);
                case "equal":
                    return new com.stardust.autojs.core.image.ColorDetector.EqualityDetector(color);
                case "diff":
                    return new com.stardust.autojs.core.image.ColorDetector.DifferenceDetector(color, threshold);
                case "rgb+":
                    return new com.stardust.autojs.core.image.ColorDetector.WeightedRGBDistanceDetector(color, threshold);
                case "hs":
                    return new com.stardust.autojs.core.image.ColorDetector.HSDistanceDetector(color, threshold);
            }
            throw new Error("Unknown algorithm: " + algorithm);
        }


        function toPointArray(points) {
            var arr = [];
            for (var i = 0; i < points.length; i++) {
                arr.push(points[i]);
            }
            return arr;
        }

        function buildRegion(region, img) {
            if (region == undefined) {
                region = [];
            }
            var x = region[0] === undefined ? 0 : region[0];
            var y = region[1] === undefined ? 0 : region[1];
            var width = region[2] === undefined ? img.getWidth() - x : region[2];
            var height = region[3] === undefined ? (img.getHeight() - y) : region[3];
            var r = new org.opencv.core.Rect(x, y, width, height);
            if (x < 0 || y < 0 || x + width > img.width || y + height > img.height) {
                throw new Error("out of region: region = [" + [x, y, width, height] + "], image.size = [" + [img.width, img.height] + "]");
            }
            return r;
        }

        function parseColor(color) {
            if (typeof (color) == 'string') {
                color = colors.parseColor(color);
            }
            return color;
        }

        function newSize(size) {
            if (!Array.isArray(size)) {
                size = [size, size];
            }
            if (size.length == 1) {
                size = [size[0], size[0]];
            }
            return new Size(size[0], size[1]);
        }

        function initIfNeeded() {
            javaImages.initOpenCvIfNeeded();
        }

        // ==================== 懒人精灵风格图色兼容支持 ====================

        /**
         * 颜色串解析：
         * 带 "#" 按 #RRGGBB（AutoX 习惯）；不带 "#" 的纯 6 位按 BBGGRR（懒人精灵习惯，文档明确此格式）。
         */
        function parseLrColorValue(str) {
            var s = String(str).trim();
            var hasSharp = s.charAt(0) === '#';
            var v = parseInt(hasSharp ? s.substring(1) : s, 16) & 0xFFFFFF;
            if (hasSharp) {
                return v;
            }
            // BBGGRR -> RRGGBB，交换 R 与 B
            return ((v & 0xFF) << 16) | (v & 0xFF00) | ((v >> 16) & 0xFF);
        }

        /** 偏色串(如 "101010") -> 逐通道容差，取三个分量的最大值（交换 R/B 不影响取最大值） */
        function lrDiffToTolerance(str) {
            var v = parseInt(String(str).trim(), 16);
            if (isNaN(v)) {
                return 0;
            }
            return Math.max((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
        }

        /** 相似度(0~1) -> 容差；未提供时返回 0（表示只按偏色匹配） */
        function lrSimToTolerance(sim) {
            if (sim === undefined || sim === null) {
                return 0;
            }
            return Math.max(0, Math.min(255, Math.round(255 * (1 - sim))));
        }

        /** 合并颜色自带偏色与相似度容差（两者取较大者） */
        function mergeTolerance(tolerances, sim) {
            var simTolerance = lrSimToTolerance(sim);
            if (simTolerance <= 0) {
                return tolerances;
            }
            return tolerances.map(function (t) {
                return Math.max(t, simTolerance);
            });
        }

        /** "778787|675699-101010" -> {colors:[...], tolerances:[...]} */
        function parseLrColorList(str) {
            var result = { colors: [], tolerances: [] };
            String(str).split("|").forEach(function (item) {
                item = item.trim();
                if (item === "") {
                    return;
                }
                var pos = item.indexOf("-");
                var colorPart = pos < 0 ? item : item.substring(0, pos);
                var diffPart = pos < 0 ? null : item.substring(pos + 1);
                result.colors.push(parseLrColorValue(colorPart));
                result.tolerances.push(diffPart == null ? 0 : lrDiffToTolerance(diffPart));
            });
            return result;
        }

        /** 把一个点的颜色项解析后追加到扁平编码：[first, second, n, color0, tol0, ...] */
        function appendLrPoint(flat, first, second, colorItems, sim) {
            var colors = [];
            var tolerances = [];
            colorItems.forEach(function (item) {
                item = String(item).trim();
                if (item === "") {
                    return;
                }
                var pos = item.indexOf("-");
                var colorPart = pos < 0 ? item : item.substring(0, pos);
                var diffPart = pos < 0 ? null : item.substring(pos + 1);
                colors.push(parseLrColorValue(colorPart));
                tolerances.push(diffPart == null ? lrSimToTolerance(sim) : lrDiffToTolerance(diffPart));
            });
            if (colors.length === 0) {
                return;
            }
            flat.push(first, second, colors.length);
            for (var i = 0; i < colors.length; i++) {
                flat.push(colors[i], tolerances[i]);
            }
        }

        /** "10|11|2F9772-000000|123456-101010,23|57|353535" -> 扁平编码（findMultiColor 用 "|"） */
        function parseLrOffsetColors(str, sim) {
            var flat = [];
            String(str).split(",").forEach(function (point) {
                var fields = point.split("|");
                if (fields.length < 3) {
                    return;
                }
                appendLrPoint(flat, parseInt(fields[0], 10), parseInt(fields[1], 10), fields.slice(2), sim);
            });
            return flat;
        }

        /** "100 200 FFFFFF 123456-000000,300 400 AABBCC" -> 扁平编码（cmpColorEx 用空格） */
        function parseLrCompareColors(str, sim) {
            var flat = [];
            String(str).split(",").forEach(function (point) {
                var fields = point.trim().split(/\s+/);
                if (fields.length < 3) {
                    return;
                }
                appendLrPoint(flat, parseInt(fields[0], 10), parseInt(fields[1], 10), fields.slice(2), sim);
            });
            return flat;
        }

        /** AutoX 风格 paths：[[dx,dy,color], ...]，color 可为字符串并支持 "|" 多候选与 "-" 偏色 */
        function buildFlatPaths(paths) {
            var flat = [];
            paths.forEach(function (p) {
                var color = p[2];
                if (typeof color === 'string' && (color.indexOf("|") >= 0 || color.indexOf("-") >= 0)) {
                    var parsed = parseLrColorList(color);
                    if (parsed.colors.length === 0) {
                        return;
                    }
                    flat.push(p[0], p[1], parsed.colors.length);
                    for (var i = 0; i < parsed.colors.length; i++) {
                        flat.push(parsed.colors[i], parsed.tolerances[i]);
                    }
                } else {
                    flat.push(p[0], p[1], 1, typeof color === 'string' ? parseLrColorValue(color) : parseColor(color), 0);
                }
            });
            return flat;
        }

        /** 颜色入参归一化：支持颜色数组、懒人风格串、"#RRGGBB"、裸 6 位 BBGGRR、颜色整数 */
        function resolveColorList(color, sim) {
            if (Array.isArray(color)) {
                var tolerances = color.map(function () {
                    return lrSimToTolerance(sim);
                });
                return { colors: color.map(toColorValue), tolerances: tolerances };
            }
            if (typeof color === 'string' && (color.indexOf("|") >= 0 || color.indexOf("-") >= 0)) {
                var parsed = parseLrColorList(color);
                return { colors: parsed.colors, tolerances: mergeTolerance(parsed.tolerances, sim) };
            }
            var tolerance = sim === undefined || sim === null ? defaultColorThreshold : lrSimToTolerance(sim);
            return { colors: [toColorValue(color)], tolerances: [tolerance] };
        }

        /** 单个颜色入参 -> 颜色整数（字符串走 parseLrColorValue，其余交给 parseColor） */
        function toColorValue(color) {
            return typeof color === 'string' ? parseLrColorValue(color) : parseColor(color);
        }

        /** 懒人精灵区域：(x2,y2) 小于等于 (x1,y1) 时视为全屏，如 (0,0,0,0) */
        function lrRegion(img, x1, y1, x2, y2) {
            x1 = x1 || 0;
            y1 = y1 || 0;
            if (x2 === undefined || x2 === null || x2 <= x1) {
                x2 = img.width;
            }
            if (y2 === undefined || y2 === null || y2 <= y1) {
                y2 = img.height;
            }
            return buildRegion([x1, y1, x2 - x1, y2 - y1], img);
        }

        function toJavaIntArray(arr) {
            var javaArray = util.java.array("int", arr.length);
            for (var i = 0; i < arr.length; i++) {
                javaArray[i] = arr[i];
            }
            return javaArray;
        }

        /** keepCapture 驻留的截图；非 null 时懒人风格查找复用它，不再重复截图 */
        var keptCaptureImage = null;

        /** all 类接口默认返回的结果数量上限 */
        var LR_ALL_LIMIT = 1000;

        function resolveLimit(options) {
            if (options && options.limit !== undefined) {
                return options.limit;
            }
            return LR_ALL_LIMIT;
        }

        /** 用当前屏幕截图执行 fn；已 keepCapture 时复用内存截图，否则用完即回收 */
        function withScreenshot(fn) {
            var img = keptCaptureImage != null ? keptCaptureImage : images.captureScreen();
            try {
                return fn(img);
            } finally {
                if (img !== keptCaptureImage) {
                    try {
                        img.recycle();
                    } catch (e) {
                        // 忽略回收异常
                    }
                }
            }
        }

        /** tb 支持数组(按 positionKeys 顺序)或对象(按同名键取值) */
        function toPositionalArgs(tb, keys) {
            if (Array.isArray(tb)) {
                return tb;
            }
            return keys.map(function (key) {
                return tb[key];
            });
        }

        /** AutoX 原生风格的重载均以图片对象作首参；数组按颜色列表处理，仍走懒人精灵风格 */
        function isImageArg(arg) {
            return arg !== null && arg !== undefined && typeof arg === 'object' && !Array.isArray(arg);
        }

        /** 参数重载：首参为图片走 AutoX 原生风格，否则走懒人精灵风格 */
        function withLrOverload(autoJsFn, lrFn) {
            return function () {
                if (isImageArg(arguments[0])) {
                    return autoJsFn.apply(images, arguments);
                }
                return lrFn.apply(null, arguments);
            };
        }

        // ---------- images.* 原生扩展 ----------

        /**
         * 统计区域内匹配颜色的像素数量。
         * @param color 颜色/颜色数组/懒人风格颜色串（"|" 多候选、"-" 偏色）
         * @param options {region, similarity, threshold}
         */
        function imageGetColorNum(img, color, options) {
            initIfNeeded();
            options = options || {};
            var list = resolveColorList(color, options.similarity);
            if (options.threshold !== undefined) {
                list.tolerances = list.tolerances.map(function () {
                    return options.threshold;
                });
            }
            var region = options.region ? buildRegion(options.region, img) : null;
            return colorFinder.getColorNum(img, toJavaIntArray(list.colors), toJavaIntArray(list.tolerances), region);
        }

        /**
         * 区域内查找所有匹配点（支持多候选颜色与查找方向）。
         * @param options {region, similarity, threshold, dir}
         */
        images.findAllColors = function (img, color, options) {
            initIfNeeded();
            options = options || {};
            var list = resolveColorList(color, options.similarity);
            if (options.threshold !== undefined) {
                list.tolerances = list.tolerances.map(function () {
                    return options.threshold;
                });
            }
            var region = options.region ? buildRegion(options.region, img) : null;
            return toPointArray(colorFinder.findAllColors(img, toJavaIntArray(list.colors),
                toJavaIntArray(list.tolerances), region, options.dir || 0, resolveLimit(options)));
        }

        /**
         * 多点找色增强版：首色与偏移点均支持多候选/偏色，并支持查找方向。
         * @param paths [[dx,dy,color], ...]，color 可为颜色或 "颜色|颜色-偏色" 串
         * @param options {region, similarity, threshold, dir, all}
         */
        images.findMultiColorsEx = function (img, firstColor, paths, options) {
            initIfNeeded();
            options = options || {};
            var first = resolveColorList(firstColor, options.similarity);
            if (options.threshold !== undefined) {
                first.tolerances = first.tolerances.map(function () {
                    return options.threshold;
                });
            }
            var region = options.region ? buildRegion(options.region, img) : null;
            var points = colorFinder.findMultiColors(img, toJavaIntArray(first.colors),
                toJavaIntArray(first.tolerances), region, toJavaIntArray(buildFlatPaths(paths)),
                options.dir || 0, !!options.all, resolveLimit(options));
            if (options.all) {
                return toPointArray(points);
            }
            return points.length > 0 ? points[0] : null;
        }

        /** 多点找色，返回全部匹配点 */
        images.findAllMultiColors = function (img, firstColor, paths, options) {
            options = options || {};
            options.all = true;
            return images.findMultiColorsEx(img, firstColor, paths, options);
        }

        // ---------- 懒人精灵同名全局函数 ----------

        function lrFindMultiColor(x1, y1, x2, y2, firstColor, offsetColor, dir, sim, all) {
            initIfNeeded();
            var first = parseLrColorList(firstColor);
            var flatPaths = parseLrOffsetColors(offsetColor, sim);
            return withScreenshot(function (img) {
                var points = colorFinder.findMultiColors(img, toJavaIntArray(first.colors),
                    toJavaIntArray(mergeTolerance(first.tolerances, sim)), lrRegion(img, x1, y1, x2, y2),
                    toJavaIntArray(flatPaths), dir || 0, !!all, LR_ALL_LIMIT);
                if (all) {
                    return toPointArray(points);
                }
                return points.length > 0 ? points[0] : null;
            });
        }

        var MULTI_COLOR_T_KEYS = ["x1", "y1", "x2", "y2", "firstColor", "sim", "offsetColor", "dir"];
        var FIND_COLOR_T_KEYS = ["x1", "y1", "x2", "y2", "color", "sim", "dir"];
        var CMP_COLOR_T_KEYS = ["mulColor", "sim"];

        images.findMultiColor = function (x1, y1, x2, y2, firstColor, offsetColor, dir, sim) {
            return lrFindMultiColor(x1, y1, x2, y2, firstColor, offsetColor, dir, sim, false);
        }

        images.findMultiColorAll = function (x1, y1, x2, y2, firstColor, offsetColor, dir, sim) {
            return lrFindMultiColor(x1, y1, x2, y2, firstColor, offsetColor, dir, sim, true);
        }

        images.findMultiColorT = function (tb) {
            var a = toPositionalArgs(tb, MULTI_COLOR_T_KEYS);
            return images.findMultiColor(a[0], a[1], a[2], a[3], a[4], a[6], a[7], a[5]);
        }

        images.findMultiColorAllT = function (tb) {
            var a = toPositionalArgs(tb, MULTI_COLOR_T_KEYS);
            return images.findMultiColorAll(a[0], a[1], a[2], a[3], a[4], a[6], a[7], a[5]);
        }

        /** findColor 兼容懒人精灵 (x1,y1,x2,y2,color,dir,sim) 与 AutoX (img,color,options) */
        images.findColor = withLrOverload(images.findColor, function (x1, y1, x2, y2, color, dir, sim) {
            initIfNeeded();
            var list = resolveColorList(color, sim);
            return withScreenshot(function (img) {
                return colorFinder.findColor(img, toJavaIntArray(list.colors), toJavaIntArray(list.tolerances),
                    lrRegion(img, x1, y1, x2, y2), dir || 0);
            });
        });

        images.findColorT = function (tb) {
            var a = toPositionalArgs(tb, FIND_COLOR_T_KEYS);
            return images.findColor(a[0], a[1], a[2], a[3], a[4], a[6], a[5]);
        }

        /** getColorNum 兼容懒人精灵 (x1,y1,x2,y2,color,sim) 与 AutoX (img,color,options) */
        images.getColorNum = withLrOverload(imageGetColorNum, function (x1, y1, x2, y2, color, sim) {
            initIfNeeded();
            var list = resolveColorList(color, sim);
            return withScreenshot(function (img) {
                return colorFinder.getColorNum(img, toJavaIntArray(list.colors), toJavaIntArray(list.tolerances),
                    lrRegion(img, x1, y1, x2, y2));
            });
        });

        /** cmpColorEx 兼容懒人精灵 (mulColor, sim)，自动截屏 */
        images.cmpColorEx = withLrOverload(function (img, mulColor, sim) {
            var flat = typeof mulColor === 'string' ? parseLrCompareColors(mulColor, sim) : mulColor;
            return colorFinder.cmpColorEx(img, toJavaIntArray(flat));
        }, function (mulColor, sim) {
            var flat = parseLrCompareColors(mulColor, sim);
            return withScreenshot(function (img) {
                return colorFinder.cmpColorEx(img, toJavaIntArray(flat));
            });
        });

        images.cmpColorExT = function (tb) {
            var a = toPositionalArgs(tb, CMP_COLOR_T_KEYS);
            return images.cmpColorEx(a[0], a[1]);
        }

        /**
         * 截图到内存并驻留，后续懒人风格查找/比色复用该截图，避免每次调用都重新截图。
         * 需要刷新时再次调用即可；用完请调用 releaseCapture() 释放，否则截图会驻留到脚本结束。
         */
        images.keepCapture = function () {
            images.releaseCapture();
            keptCaptureImage = images.captureScreen();
            return true;
        }

        /** 释放 keepCapture 驻留的截图 */
        images.releaseCapture = function () {
            if (keptCaptureImage != null) {
                try {
                    keptCaptureImage.recycle();
                } catch (e) {
                    // 忽略回收异常
                }
                keptCaptureImage = null;
            }
            return true;
        }

        scope.__asGlobal__(images, ['requestScreenCapture', 'captureScreen', 'findImage', 'findImageInRegion', 'findColor', 'findColorInRegion', 'findColorEquals', 'findMultiColors',
            'findMultiColor', 'findMultiColorAll', 'findMultiColorT', 'findMultiColorAllT', 'findColorT',
            'getColorNum', 'cmpColorEx', 'cmpColorExT', 'keepCapture', 'releaseCapture']);


        scope.colors = colors;

        return images;
    }
}