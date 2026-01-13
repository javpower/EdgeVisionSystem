package com.edge.vision.core.stitcher;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动拼接策略 (V4 最终完整版)
 * * 功能特性：
 * 1. 智能去黑边：自动识别每张图的有效内容区域。
 * 2. 垂直自适应裁切：自动计算所有图片的公共高度交集，切除因拼接错位产生的上下阶梯黑边。
 * 3. 矩阵加速：使用 OpenCV 底层矩阵运算替代循环，高性能融合。
 * 4. 完整的配置管理：支持运行时动态调整参数。
 */
public class ManualStitchStrategy implements StitchStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ManualStitchStrategy.class);

    // 线程安全的配置存储
    private final Map<Integer, CameraConfig> cameraConfigs = new ConcurrentHashMap<>();

    // 黑色背景阈值 (建议 20-40，根据现场光照调整)
    private static final int BLACK_THRESHOLD = 30;
    // 默认重叠宽度
    private static final int DEFAULT_OVERLAP_WIDTH = 100;

    // 缓存融合遮罩，避免每帧重复计算
    private Mat cachedMask = null;
    private int cachedMaskWidth = -1;
    private int cachedMaskHeight = -1;

    // 内部类：存储预处理帧及位置信息
    private static class ProcessedFrame {
        Mat image;
        Rect globalRect;    // 在无限画布上的绝对坐标
        int overlapWidth;

        ProcessedFrame(Mat image, Rect globalRect, int overlapWidth) {
            this.image = image;
            this.globalRect = globalRect;
            this.overlapWidth = overlapWidth;
        }
    }

    public ManualStitchStrategy() {
        // 默认构造
    }

    public ManualStitchStrategy(List<CameraConfig> configs) {
        if (configs != null) {
            for (CameraConfig config : configs) {
                cameraConfigs.put(config.index, config);
            }
        }
    }

    @Override
    public Mat stitch(List<Mat> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("Frames cannot be null or empty");
        }

        // 即使只有一张图，也执行去黑边逻辑
        if (frames.size() == 1) {
            return findAndCropContent(frames.get(0));
        }

        List<ProcessedFrame> processedList = new ArrayList<>();

        // 全局画布边界
        int minGlobalX = Integer.MAX_VALUE;
        int maxGlobalX = Integer.MIN_VALUE;

        // [关键] 垂直有效区域计算 (Vertical Intersection)
        // validTopY: 所有图片中，起始Y坐标最大的那个（向下压）
        // validBottomY: 所有图片中，结束Y坐标最小的那个（向上收）
        int validTopY = Integer.MIN_VALUE;
        int validBottomY = Integer.MAX_VALUE;

        int currentXCursor = 0;

        try {
            // =========================================================
            // 阶段一：预处理、坐标计算与交集范围确定
            // =========================================================
            for (int i = 0; i < frames.size(); i++) {
                Mat rawFrame = frames.get(i);
                CameraConfig config = cameraConfigs.getOrDefault(i, new CameraConfig(i));

                // 1. 智能裁切内容 (去掉四周无效黑边)
                Mat contentFrame = findAndCropContent(rawFrame);
                if (contentFrame.empty()) {
                    contentFrame.release();
                    continue; // 跳过无效帧
                }

                // 2. 变换 (旋转/缩放/翻转)
                Mat transformedFrame = applyTransform(contentFrame, config);
                contentFrame.release();

                // 3. 计算坐标
                int overlap = (i == 0) ? 0 : getOverlapWidth(i);
                int userOffsetX = (config.offset != null && config.offset.length > 0) ? config.offset[0] : 0;
                int userOffsetY = (config.offset != null && config.offset.length > 1) ? config.offset[1] : 0;

                // X轴游标逻辑：减去重叠区
                if (i > 0) currentXCursor -= overlap;

                int absX = currentXCursor + userOffsetX;
                int absY = userOffsetY; // 这里的 absY 是相对于画布原点的偏移

                int w = transformedFrame.cols();
                int h = transformedFrame.rows();

                Rect rect = new Rect(absX, absY, w, h);
                processedList.add(new ProcessedFrame(transformedFrame, rect, overlap));

                // 更新画布总宽度的边界
                minGlobalX = Math.min(minGlobalX, absX);
                maxGlobalX = Math.max(maxGlobalX, absX + w);

                // [关键] 更新垂直有效区域 (交集逻辑)
                if (i == 0) {
                    validTopY = absY;
                    validBottomY = absY + h;
                } else {
                    // 取交集：顶边取最大值(舍弃上方空白)，底边取最小值(舍弃下方空白)
                    validTopY = Math.max(validTopY, absY);
                    validBottomY = Math.min(validBottomY, absY + h);
                }

                currentXCursor += w;
            }

            if (processedList.isEmpty()) {
                return new Mat(100, 100, frames.get(0).type(), new Scalar(0));
            }

            // =========================================================
            // 阶段二：校验交集区域
            // =========================================================
            // 如果偏移量过大导致两张图在Y轴完全没交集，回退到保留所有内容
            if (validTopY >= validBottomY) {
                logger.warn("Vertical intersection is empty. Fallback to full bounding box.");
                validTopY = Integer.MAX_VALUE;
                validBottomY = Integer.MIN_VALUE;
                for (ProcessedFrame pf : processedList) {
                    validTopY = Math.min(validTopY, pf.globalRect.y);
                    validBottomY = Math.max(validBottomY, pf.globalRect.y + pf.globalRect.height);
                }
            }

            // =========================================================
            // 阶段三：合成画布 (只建立有效高度的画布)
            // =========================================================
            int canvasW = maxGlobalX - minGlobalX;
            int canvasH = validBottomY - validTopY;

            if (canvasW <= 0 || canvasH <= 0) return new Mat();

            Size canvasSize = new Size(canvasW, canvasH);
            Mat result = Mat.zeros(canvasSize, frames.get(0).type());

            // =========================================================
            // 阶段四：放置与裁切
            // =========================================================
            for (ProcessedFrame pf : processedList) {
                // 计算相对于“交集画布”的坐标
                int relX = pf.globalRect.x - minGlobalX;
                int relY = pf.globalRect.y - validTopY;

                // 调用裁切放置方法：只把落在 canvasH 范围内的部分画上去
                placeClippedImage(result, pf.image, relX, relY, pf.overlapWidth);
            }

            return result;

        } catch (Exception e) {
            logger.error("Stitch process failed", e);
            return new Mat();
        } finally {
            // 资源清理
            for (ProcessedFrame pf : processedList) {
                if (pf.image != null) pf.image.release();
            }
        }
    }

    /**
     * 放置图像，自动裁切掉超出画布高度的部分 (解决阶梯黑边)
     */
    private void placeClippedImage(Mat canvas, Mat image, int relX, int relY, int overlapWidth) {
        // 计算在画布上的有效绘制区域 (Y轴交集)
        int startY_Canvas = Math.max(0, relY);
        int endY_Canvas = Math.min(canvas.rows(), relY + image.rows());

        int drawH = endY_Canvas - startY_Canvas;
        int drawW = Math.min(image.cols(), canvas.cols() - relX);

        if (drawH <= 0 || drawW <= 0) return;

        // 计算源图像的起始 Y (如果 relY < 0，说明图像上半部分被切掉了)
        int srcOffsetY = (relY < 0) ? -relY : 0;

        Rect srcRect = new Rect(0, srcOffsetY, drawW, drawH);
        Rect canvasRect = new Rect(relX, startY_Canvas, drawW, drawH);

        Mat srcRoi = image.submat(srcRect);
        Mat canvasRoi = canvas.submat(canvasRect);

        // 融合逻辑
        if (overlapWidth <= 0 || relX == 0) {
            srcRoi.copyTo(canvasRoi);
        } else {
            // 分离融合区与实体区
            int blendW = Math.min(overlapWidth, drawW);
            int solidW = drawW - blendW;

            // 1. 融合区
            if (blendW > 0) {
                Mat cBlend = canvasRoi.submat(new Rect(0, 0, blendW, drawH));
                Mat sBlend = srcRoi.submat(new Rect(0, 0, blendW, drawH));
                blendRegionVectorized(cBlend, sBlend, blendW, drawH);
                cBlend.release();
                sBlend.release();
            }
            // 2. 实体区
            if (solidW > 0) {
                Mat cSolid = canvasRoi.submat(new Rect(blendW, 0, solidW, drawH));
                Mat sSolid = srcRoi.submat(new Rect(blendW, 0, solidW, drawH));
                sSolid.copyTo(cSolid);
                cSolid.release();
                sSolid.release();
            }
        }

        srcRoi.release();
        canvasRoi.release();
    }

    /**
     * 寻找并裁切非黑色区域
     */
    private Mat findAndCropContent(Mat src) {
        Mat gray = new Mat();
        Mat mask = new Mat();
        Mat points = new Mat();
        try {
            if (src.channels() > 1) {
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                src.copyTo(gray);
            }

            Imgproc.threshold(gray, mask, BLACK_THRESHOLD, 255, Imgproc.THRESH_BINARY);
            Core.findNonZero(mask, points);

            if (points.empty()) return new Mat();

            Rect roi = Imgproc.boundingRect(points);
            // 适当保留 Padding
            int padding = 2;
            roi.x = Math.max(0, roi.x - padding);
            roi.y = Math.max(0, roi.y - padding);
            roi.width = Math.min(src.cols() - roi.x, roi.width + padding * 2);
            roi.height = Math.min(src.rows() - roi.y, roi.height + padding * 2);

            return src.submat(roi).clone();
        } finally {
            gray.release(); mask.release(); points.release();
        }
    }

    /**
     * 矩阵化线性融合 (高性能版)
     */
    private void blendRegionVectorized(Mat bgRoi, Mat fgRoi, int width, int height) {
        if (cachedMask == null || cachedMaskWidth != width || cachedMaskHeight != height) {
            createLinearBlendMask(width, height, bgRoi.type());
        }
        Mat bgFloat = new Mat();
        Mat fgFloat = new Mat();
        Mat resultFloat = new Mat();
        Mat ones = null;
        Mat inverseMask = new Mat();
        Mat part1 = new Mat();
        Mat part2 = new Mat();

        try {
            bgRoi.convertTo(bgFloat, CvType.CV_32F);
            fgRoi.convertTo(fgFloat, CvType.CV_32F);

            // part1 = FG * Mask
            Core.multiply(fgFloat, cachedMask, part1);

            // part2 = BG * (1 - Mask)
            ones = new Mat(cachedMask.size(), cachedMask.type(), new Scalar(1.0));
            if (bgRoi.channels() > 1) {
                ones.setTo(new Scalar(1.0, 1.0, 1.0));
            }
            Core.subtract(ones, cachedMask, inverseMask);
            Core.multiply(bgFloat, inverseMask, part2);

            Core.add(part1, part2, resultFloat);
            resultFloat.convertTo(bgRoi, bgRoi.type());
        } finally {
            if (ones != null) ones.release();
            bgFloat.release(); fgFloat.release(); resultFloat.release();
            inverseMask.release(); part1.release(); part2.release();
        }
    }

    /**
     * 创建融合遮罩
     */
    private void createLinearBlendMask(int width, int height, int type) {
        if (cachedMask != null) cachedMask.release();
        cachedMaskWidth = width;
        cachedMaskHeight = height;

        float[] rowData = new float[width];
        for (int x = 0; x < width; x++) rowData[x] = (float) x / width;

        Mat rowMat = new Mat(1, width, CvType.CV_32F);
        rowMat.put(0, 0, rowData);

        Mat single = new Mat();
        Core.repeat(rowMat, height, 1, single);
        rowMat.release();

        int channels = CvType.channels(type);
        if (channels > 1) {
            List<Mat> list = new ArrayList<>();
            for (int i = 0; i < channels; i++) list.add(single);
            cachedMask = new Mat();
            Core.merge(list, cachedMask);
            single.release();
        } else {
            cachedMask = single;
        }
    }

    /**
     * 应用变换 (含包围盒修正)
     */
    private Mat applyTransform(Mat src, CameraConfig config) {
        Mat result = src.clone();

        if (config.flip != null && config.flip.length >= 2) {
            if (config.flip[0]) Core.flip(result, result, 1);
            if (config.flip[1]) Core.flip(result, result, 0);
        }

        if (config.rotation != 0 || config.scale != 1.0) {
            Point center = new Point(result.cols() / 2.0, result.rows() / 2.0);
            Rect bbox = new RotatedRect(center, result.size(), config.rotation).boundingRect();

            double nw = bbox.width * config.scale;
            double nh = bbox.height * config.scale;

            Mat M = Imgproc.getRotationMatrix2D(center, config.rotation, config.scale);
            M.put(0, 2, M.get(0, 2)[0] + (nw/2.0) - center.x);
            M.put(1, 2, M.get(1, 2)[0] + (nh/2.0) - center.y);

            Mat dst = new Mat();
            Imgproc.warpAffine(result, dst, M, new Size(Math.ceil(nw), Math.ceil(nh)),
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, new Scalar(0));

            result.release();
            M.release();
            result = dst;
        }
        return result;
    }

    private int getOverlapWidth(int i) {
        CameraConfig c = cameraConfigs.get(i);
        return (c != null && c.overlapWidth > 0) ? c.overlapWidth : DEFAULT_OVERLAP_WIDTH;
    }

    // =========================================================
    // 配置管理方法 (Public Methods)
    // =========================================================

    /**
     * 获取指定索引摄像头的配置
     */
    public CameraConfig getCameraConfig(int index) {
        return cameraConfigs.getOrDefault(index, new CameraConfig(index));
    }

    /**
     * 更新单个摄像头配置
     */
    public void updateCameraConfig(CameraConfig config) {
        if (config != null) {
            cameraConfigs.put(config.index, config);
            // 配置变更，清理缓存强制下次重新计算
            cachedMaskWidth = -1;
        }
    }

    /**
     * 获取所有摄像头配置
     */
    public Map<Integer, CameraConfig> getAllCameraConfigs() {
        return new ConcurrentHashMap<>(cameraConfigs);
    }

    /**
     * 批量设置摄像头配置
     */
    public void setAllCameraConfigs(List<CameraConfig> configs) {
        cameraConfigs.clear();
        if (configs != null) {
            for (CameraConfig config : configs) {
                cameraConfigs.put(config.index, config);
            }
        }
        cachedMaskWidth = -1;
    }

    /**
     * 重置为默认配置
     */
    public void resetToDefault(int cameraCount) {
        cameraConfigs.clear();
        for (int i = 0; i < cameraCount; i++) {
            cameraConfigs.put(i, new CameraConfig(i));
        }
        cachedMaskWidth = -1;
    }

    // =========================================================
    // 配置实体类
    // =========================================================
    public static class CameraConfig {
        public int index;
        public int[] offset = {0, 0};
        public double scale = 1.0;
        public double rotation = 0;
        public boolean[] flip = {false, false};
        public int overlapWidth = DEFAULT_OVERLAP_WIDTH;

        public CameraConfig() {}
        public CameraConfig(int i) { this.index = i; }
    }
}