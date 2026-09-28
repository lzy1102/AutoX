package com.stardust.autojs.core.image;

import android.graphics.Color;
import android.os.Build;
import androidx.annotation.RequiresApi;

import com.stardust.autojs.core.opencv.MatOfPoint;
import com.stardust.autojs.core.opencv.OpenCVHelper;
import com.stardust.util.ScreenMetrics;

import org.opencv.core.Core;

import com.stardust.autojs.core.opencv.Mat;

import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Created by Stardust on 2017/5/18.
 */

@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public class ColorFinder {

    /** all 类查找接口默认返回的结果数量上限，避免纯色背景返回海量点 */
    public static final int DEFAULT_LIMIT = 1000;

    /**
     * 多点找色走"全 native 掩码法"的首色命中数阈值。
     * 命中数较少时逐点校验更省；命中数多时逐点校验会随点数线性劣化（每次 pixel() 都是一次 JNI + 数组分配），
     * 此时改用掩码按位与，代价与命中数无关。
     */
    private static final int MASK_METHOD_CANDIDATE_THRESHOLD = 5000;

    private ScreenMetrics mScreenMetrics;

    public ColorFinder(ScreenMetrics screenMetrics) {
        mScreenMetrics = screenMetrics;
    }

    public Point findColorEquals(ImageWrapper imageWrapper, int color) {
        return findColorEquals(imageWrapper, color, null);
    }

    public Point findColorEquals(ImageWrapper imageWrapper, int color, Rect region) {
        return findColor(imageWrapper, color, 0, region);
    }

    public Point findColor(ImageWrapper imageWrapper, int color, int threshold) {
        return findColor(imageWrapper, color, threshold, null);
    }

    public Point findColor(ImageWrapper image, int color, int threshold, Rect rect) {
        MatOfPoint matOfPoint = findColorInner(image, color, threshold, rect);
        if (matOfPoint == null) {
            return null;
        }
        Point point = matOfPoint.toArray()[0];
        if (rect != null) {
            point.x = mScreenMetrics.scaleX((int) (point.x + rect.x));
            point.y = mScreenMetrics.scaleY((int) (point.y + rect.y));
        }
        OpenCVHelper.release(matOfPoint);
        return point;
    }

    public Point[] findAllPointsForColor(ImageWrapper image, int color, int threshold, Rect rect) {

        MatOfPoint matOfPoint = findColorInner(image, color, threshold, rect);
        if (matOfPoint == null) {
            return new Point[0];
        }
        Point[] points = matOfPoint.toArray();
        OpenCVHelper.release(matOfPoint);
        if (rect != null) {
            for (int i = 0; i < points.length; i++) {
                points[i].x = mScreenMetrics.scaleX((int) (points[i].x + rect.x));
                points[i].y = mScreenMetrics.scaleY((int) (points[i].y + rect.y));
            }
        }
        return points;
    }

    private MatOfPoint findColorInner(ImageWrapper image, int color, int threshold, Rect rect) {
        Mat bi = new Mat();
        Scalar lowerBound = new Scalar(Color.red(color) - threshold, Color.green(color) - threshold,
                Color.blue(color) - threshold, 255);
        Scalar upperBound = new Scalar(Color.red(color) + threshold, Color.green(color) + threshold,
                Color.blue(color) + threshold, 255);
        if (rect != null) {
            Mat m = new Mat(image.getMat(), rect);
            Core.inRange(m, lowerBound, upperBound, bi);
            OpenCVHelper.release(m);
        } else {
            Core.inRange(image.getMat(), lowerBound, upperBound, bi);
        }
        Mat nonZeroPos = new Mat();
        Core.findNonZero(bi, nonZeroPos);
        MatOfPoint result;
        if (nonZeroPos.rows() == 0 || nonZeroPos.cols() == 0) {
            result = null;
        } else {
            result = OpenCVHelper.newMatOfPoint(nonZeroPos);
        }
        OpenCVHelper.release(bi);
        OpenCVHelper.release(nonZeroPos);
        return result;
    }

    public Point findMultiColors(ImageWrapper image, int firstColor, int threshold, Rect rect, int[] points) {
        Point[] firstPoints = findAllPointsForColor(image, firstColor, threshold, rect);
        for (Point firstPoint : firstPoints) {
            if (firstPoint == null)
                continue;
            if (checksPath(image, firstPoint, threshold, rect, points)) {
                return firstPoint;
            }
        }
        return null;
    }

    private boolean checksPath(ImageWrapper image, Point startingPoint, int threshold, Rect rect, int[] points) {
        for (int i = 0; i < points.length; i += 3) {
            int x = points[i];
            int y = points[i + 1];
            int color = points[i + 2];
            ColorDetector colorDetector = new ColorDetector.DifferenceDetector(color, threshold);
            x += startingPoint.x;
            y += startingPoint.y;
            if (x >= image.getWidth() || y >= image.getHeight()
                    || x < 0 || y < 0) {
                return false;
            }
            int c = image.pixel(x, y);
            if (!colorDetector.detectsColor(Color.red(c), Color.green(c), Color.blue(c))) {
                return false;
            }

        }
        return true;
    }

    // ==================== 以下为参考懒人精灵补充的图色能力 ====================

    /**
     * 统计区域内与任意候选颜色匹配的像素数量（逐通道容差，多候选取并集）。
     * 对应懒人精灵的 getColorNum。
     */
    public int getColorNum(ImageWrapper image, int[] colors, int[] tolerances, Rect rect) {
        if (colors == null || colors.length == 0) {
            return 0;
        }
        Mat mask = new Mat();
        buildColorMask(image, colors, tolerances, rect, mask);
        int count = Core.countNonZero(mask);
        OpenCVHelper.release(mask);
        return count;
    }

    /**
     * 多候选颜色查找，按 dir 指定的方向返回第一个匹配点，找不到返回 null。
     *
     * @param dir 查找方向：0=左上到右下 1=中心向四周 2=右下到左上 3=左下到右上 4=右上到左下
     */
    public Point findColor(ImageWrapper image, int[] colors, int[] tolerances, Rect rect, int dir) {
        Point[] points = findColorCore(image, colors, tolerances, rect, null, dir, false, DEFAULT_LIMIT);
        return points.length == 0 ? null : points[0];
    }

    /**
     * 多候选颜色查找，按 dir 指定的方向返回全部匹配点（默认最多 {@link #DEFAULT_LIMIT} 个）。
     */
    public Point[] findAllColors(ImageWrapper image, int[] colors, int[] tolerances, Rect rect, int dir) {
        return findColorCore(image, colors, tolerances, rect, null, dir, true, DEFAULT_LIMIT);
    }

    /**
     * 多候选颜色查找，返回全部匹配点并限制数量上限。
     */
    public Point[] findAllColors(ImageWrapper image, int[] colors, int[] tolerances, Rect rect, int dir, int limit) {
        return findColorCore(image, colors, tolerances, rect, null, dir, true, limit);
    }

    /**
     * 多点找色：支持首色多候选、每个偏移点多候选、每点独立容差以及查找方向。
     *
     * @param flatPaths 扁平编码，逐点重复：[dx, dy, 候选数, color0, tol0, color1, tol1, ...]
     * @param all       true 返回所有匹配点（默认最多 {@link #DEFAULT_LIMIT} 个），false 只返回第一个
     * @return 匹配到的首点（坐标已按屏幕缩放），未匹配返回空数组
     */
    public Point[] findMultiColors(ImageWrapper image, int[] firstColors, int[] firstTolerances,
                                   Rect rect, int[] flatPaths, int dir, boolean all) {
        return findColorCore(image, firstColors, firstTolerances, rect, flatPaths, dir, all, DEFAULT_LIMIT);
    }

    /**
     * 多点找色，并限制 all=true 时的结果数量上限。
     */
    public Point[] findMultiColors(ImageWrapper image, int[] firstColors, int[] firstTolerances,
                                   Rect rect, int[] flatPaths, int dir, boolean all, int limit) {
        return findColorCore(image, firstColors, firstTolerances, rect, flatPaths, dir, all, limit);
    }

    /**
     * 查找核心。
     *
     * <p>先把掩码中的候选点一次性读入 int 缓冲（避免为每个像素创建 Point 对象），
     * 再按 dir 顺序选出最优或收集全部：all=false 时用 O(N) 比较取最优、不做排序；
     * all=true 时只对"已通过偏移校验的结果"排序，且受 limit 限制。
     * flatPaths 为 null 时不做偏移校验（纯单色查找）。
     */
    private Point[] findColorCore(ImageWrapper image, int[] colors, int[] tolerances, Rect rect,
                                  int[] flatPaths, int dir, boolean all, int limit) {
        if (colors == null || colors.length == 0) {
            return new Point[0];
        }
        boolean hasRect = rect != null;
        Mat src = hasRect ? new Mat(image.getMat(), rect) : image.getMat();
        Mat mask = new Mat();
        buildColorMaskFromMat(src, colors, tolerances, mask);
        int candidateCount = Core.countNonZero(mask);
        Point[] result;
        if (candidateCount == 0) {
            result = new Point[0];
        } else if (flatPaths != null && flatPaths.length > 0
                && candidateCount >= MASK_METHOD_CANDIDATE_THRESHOLD) {
            // 命中点很多：逐点校验会随点数线性劣化，改用全 native 掩码法
            result = findMultiColorsByMask(image, src, mask, rect, flatPaths, dir, all, limit);
        } else {
            result = selectFromCandidates(image, readMaskPoints(mask, rect, readLimit(all, dir, limit)),
                    rect, flatPaths, dir, all, limit);
        }
        OpenCVHelper.release(mask);
        if (hasRect) {
            OpenCVHelper.release(src);
        }
        return result;
    }

    /**
     * 本次需要从掩码中读出的候选点数量上限：
     * all=true 时只需 limit 个；all=false 且 dir=0 时按扫描序第一个即最优，只需 1 个；其余情况需要全部。
     */
    private int readLimit(boolean all, int dir, int limit) {
        if (all) {
            return limit > 0 ? limit : 0;
        }
        return dir == 0 ? 1 : 0;
    }

    /** 在候选点中按 dir 选最优或收集全部（flatPaths 非空时先做偏移校验） */
    private Point[] selectFromCandidates(ImageWrapper image, int[] candidates, Rect rect,
                                         int[] flatPaths, int dir, boolean all, int limit) {
        int count = candidates.length / 2;
        if (count == 0) {
            return new Point[0];
        }
        final double centerX = rect != null ? rect.x + rect.width / 2.0 : image.getWidth() / 2.0;
        final double centerY = rect != null ? rect.y + rect.height / 2.0 : image.getHeight() / 2.0;
        int max = limit > 0 ? limit : Integer.MAX_VALUE;
        List<Point> matched = all ? new ArrayList<>() : null;
        int bestX = 0;
        int bestY = 0;
        boolean hasBest = false;
        for (int i = 0; i < count; i++) {
            int x = candidates[i * 2];
            int y = candidates[i * 2 + 1];
            if (flatPaths != null && !checksPath(image, x, y, flatPaths)) {
                continue;
            }
            if (all) {
                if (matched.size() >= max) {
                    break;
                }
                matched.add(new Point(x, y));
            } else if (!hasBest || better(x, y, bestX, bestY, dir, centerX, centerY)) {
                bestX = x;
                bestY = y;
                hasBest = true;
            }
        }
        if (all) {
            Point[] result = matched.toArray(new Point[0]);
            sortByDirection(result, dir, rect, image);
            return scalePoints(result, rect);
        }
        if (!hasBest) {
            return new Point[0];
        }
        return scalePoints(new Point[]{new Point(bestX, bestY)}, rect);
    }

    /**
     * 多点找色的全 native 掩码法：对每个偏移点生成掩码，按 (dx,dy) 与首色掩码对齐后原位按位与；
     * 全部与完后仍为 255 的像素即完全匹配点。全程 OpenCV 运算，不做逐像素 JNI，
     * 因此耗时与首色命中数无关。
     */
    private Point[] findMultiColorsByMask(ImageWrapper image, Mat src, Mat mask, Rect rect,
                                          int[] flatPaths, int dir, boolean all, int limit) {
        int width = rect != null ? rect.width : image.getWidth();
        int height = rect != null ? rect.height : image.getHeight();
        Mat pointMask = new Mat();
        int i = 0;
        while (i < flatPaths.length) {
            int dx = flatPaths[i++];
            int dy = flatPaths[i++];
            int count = flatPaths[i++];
            int[] colors = new int[count];
            int[] tolerances = new int[count];
            for (int k = 0; k < count; k++) {
                colors[k] = flatPaths[i++];
                tolerances[k] = flatPaths[i++];
            }
            // 该偏移下首色点仍然合法的范围 [x0, x1) × [y0, y1)
            int x0 = Math.max(0, -dx);
            int x1 = Math.min(width, width - dx);
            int y0 = Math.max(0, -dy);
            int y1 = Math.min(height, height - dy);
            // 越界边缘一定不匹配，先清零
            clearOutside(mask, width, height, x0, x1, y0, y1);
            if (x1 <= x0 || y1 <= y0) {
                break;
            }
            buildColorMaskFromMat(src, colors, tolerances, pointMask);
            // (x, y) 与 (x + dx, y + dy) 对应，直接对重叠区按位与
            Mat aRoi = new Mat(mask, new Rect(x0, y0, x1 - x0, y1 - y0));
            Mat bRoi = new Mat(pointMask, new Rect(x0 + dx, y0 + dy, x1 - x0, y1 - y0));
            Core.bitwise_and(aRoi, bRoi, aRoi);
            OpenCVHelper.release(aRoi);
            OpenCVHelper.release(bRoi);
        }
        OpenCVHelper.release(pointMask);
        return selectFromCandidates(image, readMaskPoints(mask, rect, readLimit(all, dir, limit)),
                rect, null, dir, all, limit);
    }

    /** 把掩码中"当前偏移下必然越界"的边缘区域清零 */
    private void clearOutside(Mat mask, int width, int height, int x0, int x1, int y0, int y1) {
        if (x0 > 0) {
            mask.colRange(0, x0).setTo(new Scalar(0));
        }
        if (x1 < width) {
            mask.colRange(x1, width).setTo(new Scalar(0));
        }
        if (y0 > 0) {
            mask.rowRange(0, y0).setTo(new Scalar(0));
        }
        if (y1 < height) {
            mask.rowRange(y1, height).setTo(new Scalar(0));
        }
    }

    /** 判断候选点是否比当前最优更符合 dir 指定的查找方向 */
    private static boolean better(int x, int y, int bestX, int bestY, int dir, double centerX, double centerY) {
        switch (dir) {
            case 1: {
                double dx1 = x - centerX;
                double dy1 = y - centerY;
                double dx2 = bestX - centerX;
                double dy2 = bestY - centerY;
                return dx1 * dx1 + dy1 * dy1 < dx2 * dx2 + dy2 * dy2;
            }
            case 2:
                return y > bestY || (y == bestY && x > bestX);
            case 3:
                return y > bestY || (y == bestY && x < bestX);
            case 4:
                return y < bestY || (y == bestY && x > bestX);
            default:
                return y < bestY || (y == bestY && x < bestX);
        }
    }

    /**
     * 指定坐标点颜色比对：每个点只要命中其任一候选颜色（逐通道容差）即算通过。
     * 对应懒人精灵的 cmpColorEx。
     *
     * @param flatPoints 扁平编码，逐点重复：[x, y, 候选数, color0, tol0, color1, tol1, ...]
     * @return 全部点都通过返回 true
     */
    public boolean cmpColorEx(ImageWrapper image, int[] flatPoints) {
        int i = 0;
        while (i < flatPoints.length) {
            int x = flatPoints[i++];
            int y = flatPoints[i++];
            int count = flatPoints[i++];
            if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
                return false;
            }
            int c = image.pixel(x, y);
            int r = Color.red(c);
            int g = Color.green(c);
            int b = Color.blue(c);
            boolean matched = false;
            for (int k = 0; k < count; k++) {
                int color = flatPoints[i++];
                int tolerance = flatPoints[i++];
                if (matches(color, tolerance, r, g, b)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从掩码读取命中点，返回扁平缓冲 [x0, y0, x1, y1, ...]（图像坐标，含 rect 偏移、未做屏幕缩放）。
     * maxPoints > 0 时最多只读 maxPoints 个，避免为大结果集物化整块缓冲。
     */
    private int[] readMaskPoints(Mat mask, Rect rect, int maxPoints) {
        Mat nonZeroPos = new Mat();
        Core.findNonZero(mask, nonZeroPos);
        int total = (int) nonZeroPos.total();
        int count = maxPoints > 0 ? Math.min(total, maxPoints) : total;
        int[] buffer = new int[count * 2];
        if (count > 0) {
            nonZeroPos.get(0, 0, buffer);
        }
        OpenCVHelper.release(nonZeroPos);
        int offsetX = rect != null ? rect.x : 0;
        int offsetY = rect != null ? rect.y : 0;
        if (offsetX != 0 || offsetY != 0) {
            for (int i = 0; i < count; i++) {
                buffer[i * 2] += offsetX;
                buffer[i * 2 + 1] += offsetY;
            }
        }
        return buffer;
    }

    /** 将多个候选颜色按 {color ± tolerance} 取并集，生成单通道掩码（src 是区域或整图视图，由调用方释放） */
    private void buildColorMaskFromMat(Mat src, int[] colors, int[] tolerances, Mat mask) {
        Mat tmp = new Mat();
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            int tolerance = tolerances != null && i < tolerances.length ? tolerances[i] : 0;
            Core.inRange(src,
                    new Scalar(Color.red(color) - tolerance, Color.green(color) - tolerance,
                            Color.blue(color) - tolerance, 255),
                    new Scalar(Color.red(color) + tolerance, Color.green(color) + tolerance,
                            Color.blue(color) + tolerance, 255),
                    tmp);
            if (i == 0) {
                tmp.copyTo(mask);
            } else {
                Core.bitwise_or(mask, tmp, mask);
            }
        }
        OpenCVHelper.release(tmp);
    }

    /** 将多个候选颜色按 {color ± tolerance} 取并集，生成单通道掩码 */
    private void buildColorMask(ImageWrapper image, int[] colors, int[] tolerances, Rect rect, Mat mask) {
        boolean hasRect = rect != null;
        Mat src = hasRect ? new Mat(image.getMat(), rect) : image.getMat();
        buildColorMaskFromMat(src, colors, tolerances, mask);
        if (hasRect) {
            OpenCVHelper.release(src);
        }
    }

    private Point[] scalePoints(Point[] points, Rect rect) {
        if (rect != null) {
            for (Point point : points) {
                point.x = mScreenMetrics.scaleX((int) point.x);
                point.y = mScreenMetrics.scaleY((int) point.y);
            }
        }
        return points;
    }

    /** 按查找方向对候选点排序 */
    private void sortByDirection(Point[] points, int dir, Rect rect, ImageWrapper image) {
        if (points.length < 2) {
            return;
        }
        final double centerX = rect != null ? rect.x + rect.width / 2.0 : image.getWidth() / 2.0;
        final double centerY = rect != null ? rect.y + rect.height / 2.0 : image.getHeight() / 2.0;
        Comparator<Point> comparator;
        switch (dir) {
            case 1:
                comparator = (a, b) -> Double.compare(distance2(a, centerX, centerY), distance2(b, centerX, centerY));
                break;
            case 2:
                comparator = (a, b) -> a.y != b.y ? Double.compare(b.y, a.y) : Double.compare(b.x, a.x);
                break;
            case 3:
                comparator = (a, b) -> a.y != b.y ? Double.compare(b.y, a.y) : Double.compare(a.x, b.x);
                break;
            case 4:
                comparator = (a, b) -> a.y != b.y ? Double.compare(a.y, b.y) : Double.compare(b.x, a.x);
                break;
            default:
                comparator = (a, b) -> a.y != b.y ? Double.compare(a.y, b.y) : Double.compare(a.x, b.x);
                break;
        }
        Arrays.sort(points, comparator);
    }

    private static double distance2(Point point, double centerX, double centerY) {
        double dx = point.x - centerX;
        double dy = point.y - centerY;
        return dx * dx + dy * dy;
    }

    /** 校验偏移路径，支持每个偏移点的多候选颜色与独立容差（逐通道比较） */
    private boolean checksPath(ImageWrapper image, int startX, int startY, int[] flatPaths) {
        int i = 0;
        while (i < flatPaths.length) {
            int dx = flatPaths[i++];
            int dy = flatPaths[i++];
            int count = flatPaths[i++];
            int x = startX + dx;
            int y = startY + dy;
            if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
                return false;
            }
            int c = image.pixel(x, y);
            int r = Color.red(c);
            int g = Color.green(c);
            int b = Color.blue(c);
            boolean matched = false;
            for (int k = 0; k < count; k++) {
                int color = flatPaths[i++];
                int tolerance = flatPaths[i++];
                if (matches(color, tolerance, r, g, b)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(int color, int tolerance, int r, int g, int b) {
        return Math.abs(Color.red(color) - r) <= tolerance
                && Math.abs(Color.green(color) - g) <= tolerance
                && Math.abs(Color.blue(color) - b) <= tolerance;
    }
}