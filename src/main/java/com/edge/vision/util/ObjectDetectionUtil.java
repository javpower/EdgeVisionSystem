package com.edge.vision.util;

import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 工件检测工具类
 * <p>
 * 封装 IndustrialObjectDetector，提供简洁的 API
 */
@Component
public class ObjectDetectionUtil {

    private static final Logger logger = LoggerFactory.getLogger(ObjectDetectionUtil.class);
    private final IndustrialObjectDetector detector;

    public ObjectDetectionUtil() {
        this.detector = new IndustrialObjectDetector();
    }

    /**
     * 检测结果
     */
    public static class DetectionResult {
        public boolean success;
        public String message;
        public int matchedCount;
        public double[][] corners;  // [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]

        public DetectionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * 根据模板检测图像中的工件
     *
     * @param imagePath        待检测图像路径
     * @param templatePath     工件模板小图路径
     * @return 检测结果（包含四个角坐标）
     */
    public DetectionResult detectWorkpiece(String imagePath, String templatePath) {
        logger.info("检测工件: image={}, template={}", imagePath, templatePath);

        // 验证文件存在
        if (!new File(imagePath).exists()) {
            return new DetectionResult(false, "图像文件不存在: " + imagePath);
        }
        if (!new File(templatePath).exists()) {
            return new DetectionResult(false, "模板文件不存在: " + templatePath);
        }

        IndustrialObjectDetector.DetectionResult result =
            detector.detectObject(templatePath, imagePath, null);

        if (!result.success) {
            logger.warn("工件检测失败: {}", result.message);
            return new DetectionResult(false, result.message);
        }

        // 转换角点坐标
        DetectionResult detectionResult = new DetectionResult(true, "检测成功");
        detectionResult.matchedCount = result.matchedCount;
        detectionResult.corners = new double[4][2];
        for (int i = 0; i < 4; i++) {
            detectionResult.corners[i][0] = result.corners[i].x;
            detectionResult.corners[i][1] = result.corners[i].y;
        }

        logger.info("工件检测成功: corners={}, matchedCount={}",
            java.util.Arrays.deepToString(detectionResult.corners), detectionResult.matchedCount);

        // 释放资源
        if (result.resultImage != null) {
            result.resultImage.release();
        }

        return detectionResult;
    }

    /**
     * 根据四个角坐标裁剪图像
     *
     * @param imagePath   原图像路径
     * @param corners     四个角坐标 [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
     * @param outputPath  输出路径（如果为null则不保存）
     * @param padding     扩边像素
     * @return 裁剪后的图像（调用者需要释放）
     */
    public Mat cropImageByCorners(String imagePath, double[][] corners, String outputPath, int padding) {
        logger.info("裁剪图像: image={}, corners={}, padding={}", imagePath, corners, padding);

        Mat fullImage = Imgcodecs.imread(imagePath);
        if (fullImage.empty()) {
            throw new RuntimeException("无法读取图像: " + imagePath);
        }

        // 计算边界框
        double minX = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = Double.MIN_VALUE;

        for (double[] corner : corners) {
            minX = Math.min(minX, corner[0]);
            maxX = Math.max(maxX, corner[0]);
            minY = Math.min(minY, corner[1]);
            maxY = Math.max(maxY, corner[1]);
        }

        // 添加 padding
        int x0 = Math.max(0, (int)(minX - padding));
        int y0 = Math.max(0, (int)(minY - padding));
        int x1 = Math.min(fullImage.cols(), (int)(maxX + padding) + 1);
        int y1 = Math.min(fullImage.rows(), (int)(maxY + padding) + 1);

        int width = x1 - x0;
        int height = y1 - y0;

        // 裁剪
        org.opencv.core.Rect roi = new org.opencv.core.Rect(x0, y0, width, height);
        Mat cropped = new Mat(fullImage, roi).clone();

        logger.info("裁剪完成: original=({},{}), crop=({},{},{}x{}), result=({}x{})",
            fullImage.cols(), fullImage.rows(), x0, y0, width, height, cropped.cols(), cropped.rows());

        // 保存（如果指定了输出路径）
        if (outputPath != null && !outputPath.isEmpty()) {
            Imgcodecs.imwrite(outputPath, cropped);
            logger.info("裁剪图像已保存: {}", outputPath);
        }

        fullImage.release();
        return cropped;
    }

    /**
     * 释放 Mat 资源
     */
    public static void release(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null && !mat.empty()) {
                mat.release();
            }
        }
    }
}
