package com.edge.vision.core.stitcher;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动拼接策略
 * 支持前端手动调节拼接参数，包括：
 * - 位置偏移 (offset)
 * - 缩放比例 (scale)
 * - 旋转角度 (rotation)
 * - 翻转 (flip)
 * - 重叠区域宽度 (overlap-width)
 *
 * 参数支持运行时动态更新和持久化
 */
public class ManualStitchStrategy implements StitchStrategy {

    // 摄像头配置参数存储
    private final Map<Integer, CameraConfig> cameraConfigs = new ConcurrentHashMap<>();

    // 默认配置
    private static final int DEFAULT_OVERLAP_WIDTH = 100;
    private static final int DEFAULT_BLEND_WIDTH = 100;

    public ManualStitchStrategy() {
        // 默认配置初始化
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

        if (frames.size() == 1) {
            return frames.get(0).clone();
        }

        List<Mat> processedFrames = new ArrayList<>();

        // 1. 应用每个摄像头的变换
        for (int i = 0; i < frames.size(); i++) {
            Mat frame = frames.get(i);
            CameraConfig config = cameraConfigs.getOrDefault(i, new CameraConfig(i));

            Mat processed = applyTransform(frame, config);
            processedFrames.add(processed);
        }

        // 2. 计算画布大小
        Size canvasSize = calculateCanvasSize(processedFrames, cameraConfigs);

        // 3. 创建画布并拼接
        Mat result = new Mat(canvasSize, processedFrames.get(0).type(), new Scalar(0));

        // 4. 放置每个摄像头图像
        for (int i = 0; i < processedFrames.size(); i++) {
            Mat frame = processedFrames.get(i);
            CameraConfig config = cameraConfigs.getOrDefault(i, new CameraConfig(i));

            placeImage(result, frame, config, i == 0 ? 0 : getOverlapWidth(i));
        }

        // 5. 清理
        for (Mat mat : processedFrames) {
            mat.release();
        }

        return result;
    }

    /**
     * 应用变换到单个图像
     */
    private Mat applyTransform(Mat src, CameraConfig config) {
        Mat result = src.clone();

        // 1. 翻转
        if (config.flip != null && config.flip.length >= 2) {
            if (config.flip[0]) { // 水平翻转
                Core.flip(result, result, 1);
            }
            if (config.flip[1]) { // 垂直翻转
                Core.flip(result, result, 0);
            }
        }

        // 2. 旋转
        if (config.rotation != 0) {
            Point center = new Point(result.cols() / 2.0, result.rows() / 2.0);
            Mat rotationMatrix = Imgproc.getRotationMatrix2D(center, config.rotation, config.scale);
            Imgproc.warpAffine(result, result, rotationMatrix, result.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, new Scalar(0));
            rotationMatrix.release();
        } else if (config.scale != 1.0) {
            // 3. 仅缩放
            Size newSize = new Size(result.cols() * config.scale, result.rows() * config.scale);
            Imgproc.resize(result, result, newSize, 0, 0, Imgproc.INTER_LINEAR);
        }

        return result;
    }

    /**
     * 计算画布大小
     */
    private Size calculateCanvasSize(List<Mat> frames, Map<Integer, CameraConfig> configs) {
        double totalWidth = 0;
        double maxHeight = 0;

        for (int i = 0; i < frames.size(); i++) {
            Mat frame = frames.get(i);
            CameraConfig config = configs.getOrDefault(i, new CameraConfig(i));

            double effectiveWidth = frame.cols();
            if (i > 0) {
                int overlap = getOverlapWidth(i);
                effectiveWidth -= overlap;
            }

            totalWidth += effectiveWidth;
            maxHeight = Math.max(maxHeight, frame.rows() + (config.offset != null ? config.offset[1] : 0));
        }

        return new Size(totalWidth, maxHeight);
    }

    /**
     * 将图像放置到画布上
     */
    private void placeImage(Mat canvas, Mat image, CameraConfig config, int overlapWidth) {
        int xOffset = config.offset != null ? config.offset[0] : 0;
        int yOffset = config.offset != null ? config.offset[1] : 0;

        // 计算当前图像应该放置的 x 位置
        int currentX = xOffset;
        for (int i = 0; i < config.index; i++) {
            CameraConfig prevConfig = cameraConfigs.getOrDefault(i, new CameraConfig(i));
            int prevOverlap = i > 0 ? getOverlapWidth(i) : 0;
            currentX += prevConfig.getImageWidth() - prevOverlap;
        }

        // 确保不越界
        int x = Math.max(0, currentX);
        int y = Math.max(0, yOffset);

        // 计算实际可以放置的宽度和高度
        int width = Math.min(image.cols(), canvas.cols() - x);
        int height = Math.min(image.rows(), canvas.rows() - y);

        if (width > 0 && height > 0) {
            // 获取目标区域
            Mat roi = new Mat(canvas, new Rect(x, y, width, height));

            // 获取源图像对应区域
            Mat srcRoi = new Mat(image, new Rect(0, 0, width, height));

            // 如果有重叠区域且不是第一个图像，进行融合
            if (config.index > 0 && overlapWidth > 0 && x > 0) {
                blendOverlap(canvas, image, x, y, width, height, overlapWidth);
            } else {
                // 直接复制
                srcRoi.copyTo(roi);
            }

            roi.release();
            srcRoi.release();
        }
    }

    /**
     * 融合重叠区域
     */
    private void blendOverlap(Mat canvas, Mat image, int x, int y, int width, int height, int overlapWidth) {
        int blendStartX = Math.max(0, x - overlapWidth);
        int actualOverlapWidth = Math.min(overlapWidth, width);

        if (actualOverlapWidth <= 0) return;

        for (int ox = 0; ox < actualOverlapWidth; ox++) {
            int canvasX = blendStartX + ox;
            int imageX = ox;

            if (canvasX >= canvas.cols() || imageX >= image.cols()) continue;

            // 计算融合权重
            float alpha1 = 1.0f - (float) ox / actualOverlapWidth;
            float alpha2 = (float) ox / actualOverlapWidth;

            // 对每个通道进行融合
            for (int c = 0; c < image.channels(); c++) {
                Mat canvasChannel = new Mat();
                Mat imageChannel = new Mat();
                Core.extractChannel(canvas.rowRange(y, Math.min(y + height, canvas.rows())).col(canvasX), canvasChannel, c);
                Core.extractChannel(image.rowRange(0, Math.min(height, image.rows())).col(imageX), imageChannel, c);

                Mat blended = new Mat();
                Core.addWeighted(canvasChannel, alpha1, imageChannel, alpha2, 0, blended);

                blended.copyTo(canvas.rowRange(y, Math.min(y + height, canvas.rows())).col(canvasX).row(c).colRange(0, 1));

                canvasChannel.release();
                imageChannel.release();
                blended.release();
            }
        }

        // 放置非重叠部分
        int nonOverlapStart = actualOverlapWidth;
        int remainingWidth = width - nonOverlapStart;

        if (remainingWidth > 0) {
            Mat roi = new Mat(canvas, new Rect(x + nonOverlapStart, y, remainingWidth, Math.min(height, canvas.rows() - y)));
            Mat srcRoi = new Mat(image, new Rect(nonOverlapStart, 0, remainingWidth, Math.min(height, image.rows())));
            srcRoi.copyTo(roi);
            roi.release();
            srcRoi.release();
        }
    }

    private int getOverlapWidth(int cameraIndex) {
        CameraConfig config = cameraConfigs.get(cameraIndex);
        if (config != null && config.overlapWidth > 0) {
            return config.overlapWidth;
        }
        return DEFAULT_OVERLAP_WIDTH;
    }

    // ================== 配置管理方法 ==================

    /**
     * 更新摄像头配置
     */
    public void updateCameraConfig(CameraConfig config) {
        cameraConfigs.put(config.index, config);
    }

    /**
     * 获取摄像头配置
     */
    public CameraConfig getCameraConfig(int index) {
        return cameraConfigs.getOrDefault(index, new CameraConfig(index));
    }

    /**
     * 获取所有摄像头配置
     */
    public Map<Integer, CameraConfig> getAllCameraConfigs() {
        return new ConcurrentHashMap<>(cameraConfigs);
    }

    /**
     * 设置所有摄像头配置
     */
    public void setAllCameraConfigs(List<CameraConfig> configs) {
        cameraConfigs.clear();
        if (configs != null) {
            for (CameraConfig config : configs) {
                cameraConfigs.put(config.index, config);
            }
        }
    }

    /**
     * 重置为默认配置
     */
    public void resetToDefault(int cameraCount) {
        cameraConfigs.clear();
        for (int i = 0; i < cameraCount; i++) {
            cameraConfigs.put(i, new CameraConfig(i));
        }
    }

    // ================== 配置类 ==================

    public static class CameraConfig {
        public int index;
        public int[] offset = {0, 0};
        public double scale = 1.0;
        public double rotation = 0;
        public boolean[] flip = {false, false};
        public int overlapWidth = DEFAULT_OVERLAP_WIDTH;
        private transient int imageWidth = 640; // 默认图像宽度

        public CameraConfig() {}

        public CameraConfig(int index) {
            this.index = index;
        }

        public int getImageWidth() {
            return imageWidth;
        }

        public void setImageWidth(int width) {
            this.imageWidth = width;
        }
    }
}
