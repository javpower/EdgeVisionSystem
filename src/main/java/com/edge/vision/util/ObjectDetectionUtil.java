package com.edge.vision.util;

import org.opencv.core.*;
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
     * @param image            待检测图像 Mat
     * @param templatePath     工件模板小图路径
     * @return 检测结果（包含四个角坐标）
     */
    public DetectionResult detectWorkpiece(Mat image, String templatePath) {
        logger.info("检测工件(Mat): template={}", templatePath);

        // 验证模板文件存在
        if (!new File(templatePath).exists()) {
            return new DetectionResult(false, "模板文件不存在: " + templatePath);
        }

        // 验证 Mat 有效
        if (image.empty()) {
            return new DetectionResult(false, "图像 Mat 为空");
        }

        // 读取模板图像
        Mat templateImage = Imgcodecs.imread(templatePath);
        if (templateImage.empty()) {
            return new DetectionResult(false, "无法读取模板图像: " + templatePath);
        }

        try {
            IndustrialObjectDetector.DetectionResult result =
                detector.detectObject(templateImage, image);

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
            if(result.resultImage!=null){
                result.resultImage.release();
            }
            logger.info("工件检测成功: corners={}, matchedCount={}",
                java.util.Arrays.deepToString(detectionResult.corners), detectionResult.matchedCount);
            return detectionResult;

        } finally {
            templateImage.release();
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
        return cropImageByCorners(fullImage,corners,outputPath,padding);
    }
    public Mat cropImageByCorners(Mat fullImage, double[][] corners, String outputPath, int padding) {
        // corners 顺序：[左上, 右上, 右下, 左下]
        // 使用透视变换把倾斜的工件"摆正"

        // 计算目标矩形的宽度和高度
        double widthTop = Math.sqrt(Math.pow(corners[1][0] - corners[0][0], 2) + Math.pow(corners[1][1] - corners[0][1], 2));
        double widthBottom = Math.sqrt(Math.pow(corners[2][0] - corners[3][0], 2) + Math.pow(corners[2][1] - corners[3][1], 2));
        double heightLeft = Math.sqrt(Math.pow(corners[3][0] - corners[0][0], 2) + Math.pow(corners[3][1] - corners[0][1], 2));
        double heightRight = Math.sqrt(Math.pow(corners[2][0] - corners[1][0], 2) + Math.pow(corners[2][1] - corners[1][1], 2));

        int width = (int) Math.max(widthTop, widthBottom) + padding * 2;
        int height = (int) Math.max(heightLeft, heightRight) + padding * 2;

        // 源点（图像中的四个角）
        Mat srcPoints = new Mat(4, 1, CvType.CV_32FC2);
        srcPoints.put(0, 0, corners[0][0], corners[0][1]); // 左上
        srcPoints.put(1, 0, corners[1][0], corners[1][1]); // 右上
        srcPoints.put(2, 0, corners[2][0], corners[2][1]); // 右下
        srcPoints.put(3, 0, corners[3][0], corners[3][1]); // 左下

        // 目标点（摆正后的矩形）
        Mat dstPoints = new Mat(4, 1, CvType.CV_32FC2);
        dstPoints.put(0, 0, padding, padding);                          // 左上
        dstPoints.put(1, 0, width - padding, padding);                  // 右上
        dstPoints.put(2, 0, width - padding, height - padding);         // 右下
        dstPoints.put(3, 0, padding, height - padding);                 // 左下

        // 计算透视变换矩阵
        Mat perspectiveMatrix = org.opencv.calib3d.Calib3d.findHomography(
            new MatOfPoint2f(srcPoints),
            new MatOfPoint2f(dstPoints)
        );

        // 执行透视变换
        Mat cropped = new Mat();
        org.opencv.imgproc.Imgproc.warpPerspective(fullImage, cropped, perspectiveMatrix, new org.opencv.core.Size(width, height));

        srcPoints.release();
        dstPoints.release();
        perspectiveMatrix.release();

        logger.info("透视变换裁剪完成: original=({}x{}), result=({}x{})",
            fullImage.cols(), fullImage.rows(), cropped.cols(), cropped.rows());

        // 保存（如果指定了输出路径）- 使用 PNG 无损格式
        if (outputPath != null && !outputPath.isEmpty()) {
            // 根据输出路径扩展名选择格式
            String ext = outputPath.toLowerCase();
            boolean isPng = ext.endsWith(".png");

            if (isPng) {
                // PNG 无损压缩
                int[] params = new int[]{Imgcodecs.IMWRITE_PNG_COMPRESSION, 3}; // 0-9, 3 是平衡速度和压缩率
                Imgcodecs.imwrite(outputPath, cropped, new MatOfInt(params));
                logger.info("裁剪图像已保存（PNG无损）: {}", outputPath);
            } else {
                // JPEG 格式（有损，但兼容性好）
                MatOfByte mob = new MatOfByte();
                int[] params = new int[]{Imgcodecs.IMWRITE_JPEG_QUALITY, 100};
                Imgcodecs.imencode(".jpg", cropped, mob, new MatOfInt(params));
                byte[] bytes = mob.toArray();
                mob.release();

                try {
                    java.nio.file.Files.write(
                        java.nio.file.Paths.get(outputPath),
                        bytes
                    );
                    logger.info("裁剪图像已保存（JPEG质量100）: {}", outputPath);
                } catch (Exception e) {
                    logger.warn("保存图像失败，尝试默认方式: {}", e.getMessage());
                    Imgcodecs.imwrite(outputPath, cropped);
                }
            }
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
