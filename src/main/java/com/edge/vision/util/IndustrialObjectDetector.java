package com.edge.vision.util;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.Feature2D;
import org.opencv.features2d.SIFT;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 工业工件特征检测服务
 * 特点：内存安全、结构化返回、高鲁棒性
 */
public class IndustrialObjectDetector {


//    static {
//        try {
//            // 根据你的环境选择加载方式
//            nu.pattern.OpenCV.loadShared();
//            // System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
//        } catch (Throwable e) {
//            e.printStackTrace();
//            System.exit(1);
//        }
//    }

    /**
     * 检测结果封装类 (DTO)
     */
    public static class DetectionResult {
        public boolean success;        // 是否找到
        public int matchedCount;       // 匹配到的特征点数量
        public Mat resultImage;        // 画了框的结果图 (需要调用者释放)
        public Point[] corners;        // 工件在图中的四个角坐标
        public String message;         // 状态消息

        public DetectionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * 执行检测的主方法（Mat 版本）
     *
     * @param templateImage 工件截图 Mat (小图)
     * @param sceneImage    现场整图 Mat (大图)
     * @return 检测结果对象
     */
    public DetectionResult detectObject(Mat templateImage, Mat sceneImage) {
        // 定义所有需要手动释放的 Mat 对象，防止内存泄漏
        Mat imgTemplateGray = null;
        Mat imgSceneGray = null;
        Mat descriptorsTemplate = null;
        Mat descriptorsScene = null;
        MatOfKeyPoint keypointsTemplate = null;
        MatOfKeyPoint keypointsScene = null;
        Mat hMatrix = null;
        try {
            // 1. 图片校验
            if (templateImage.empty() || sceneImage.empty()) {
                return new DetectionResult(false, "图像 Mat 为空");
            }

            // 2. 转换为灰度图用于特征提取
            imgTemplateGray = new Mat();
            imgSceneGray = new Mat();
            Imgproc.cvtColor(templateImage, imgTemplateGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(sceneImage, imgSceneGray, Imgproc.COLOR_BGR2GRAY);

            // 3. 初始化特征检测器
            Feature2D detector = SIFT.create();

            keypointsTemplate = new MatOfKeyPoint();
            keypointsScene = new MatOfKeyPoint();
            descriptorsTemplate = new Mat();
            descriptorsScene = new Mat();

            // 4. 检测特征点并计算描述子
            detector.detectAndCompute(imgTemplateGray, new Mat(), keypointsTemplate, descriptorsTemplate);
            detector.detectAndCompute(imgSceneGray, new Mat(), keypointsScene, descriptorsScene);

            if (descriptorsTemplate.empty() || descriptorsScene.empty()) {
                return new DetectionResult(false, "无法提取特征点，图片可能过于模糊或无纹理");
            }

            // 5. 特征匹配
            DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
            List<MatOfDMatch> knnMatches = new ArrayList<>();

            if (descriptorsTemplate.type() != CvType.CV_32F) {
                descriptorsTemplate.convertTo(descriptorsTemplate, CvType.CV_32F);
            }
            if (descriptorsScene.type() != CvType.CV_32F) {
                descriptorsScene.convertTo(descriptorsScene, CvType.CV_32F);
            }

            matcher.knnMatch(descriptorsTemplate, descriptorsScene, knnMatches, 2);

            // 6. Lowe's Ratio Test
            float ratioThresh = 0.7f;
            List<DMatch> listOfGoodMatches = new ArrayList<>();
            for (MatOfDMatch match : knnMatches) {
                DMatch[] dmatches = match.toArray();
                if (dmatches.length >= 2 && dmatches[0].distance < ratioThresh * dmatches[1].distance) {
                    listOfGoodMatches.add(dmatches[0]);
                }
            }

            // 7. 校验匹配数量
            if (listOfGoodMatches.size() < 4) {
                return new DetectionResult(false, "匹配点不足 (" + listOfGoodMatches.size() + "<4)，无法定位工件");
            }

            // 8. 计算单应性矩阵
            List<Point> objPoints = new ArrayList<>();
            List<Point> scenePoints = new ArrayList<>();
            List<KeyPoint> listOfKeypointsTemplate = keypointsTemplate.toList();
            List<KeyPoint> listOfKeypointsScene = keypointsScene.toList();

            for (DMatch goodMatch : listOfGoodMatches) {
                objPoints.add(listOfKeypointsTemplate.get(goodMatch.queryIdx).pt);
                scenePoints.add(listOfKeypointsScene.get(goodMatch.trainIdx).pt);
            }

            MatOfPoint2f objMat = new MatOfPoint2f();
            objMat.fromList(objPoints);
            MatOfPoint2f sceneMat = new MatOfPoint2f();
            sceneMat.fromList(scenePoints);

            hMatrix = Calib3d.findHomography(objMat, sceneMat, Calib3d.RANSAC, 5.0);

            if (hMatrix.empty()) {
                return new DetectionResult(false, "无法计算空间变换矩阵");
            }

            // 9. 坐标映射
            Mat objCorners = new Mat(4, 1, CvType.CV_32FC2);
            Mat sceneCorners = new Mat(4, 1, CvType.CV_32FC2);

            objCorners.put(0, 0, new double[]{0, 0});
            objCorners.put(1, 0, new double[]{templateImage.cols(), 0});
            objCorners.put(2, 0, new double[]{templateImage.cols(), templateImage.rows()});
            objCorners.put(3, 0, new double[]{0, templateImage.rows()});

            Core.perspectiveTransform(objCorners, sceneCorners, hMatrix);

            // 10. 绘图
            Point[] corners = new Point[4];
            for (int i = 0; i < 4; i++) {
                corners[i] = new Point(sceneCorners.get(i, 0));
            }
            DetectionResult result = new DetectionResult(true, "检测成功");
            result.matchedCount = listOfGoodMatches.size();
            result.corners = corners;
            result.resultImage = sceneImage;

            objCorners.release();
            sceneCorners.release();
            objMat.release();
            sceneMat.release();

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return new DetectionResult(false, "发生异常: " + e.getMessage());
        } finally {
            safeRelease(imgTemplateGray);
            safeRelease(imgSceneGray);
            safeRelease(descriptorsTemplate);
            safeRelease(descriptorsScene);
            safeRelease(keypointsTemplate);
            safeRelease(keypointsScene);
            safeRelease(hMatrix);
        }
    }

    /**
     * 执行检测的主方法（文件路径版本）
     *
     * @param templatePath 工件截图路径 (小图)
     * @param scenePath    现场整图路径 (大图)
     * @param outputPath   结果保存路径 (可选)
     * @return 检测结果对象
     */
    public DetectionResult detectObject(String templatePath, String scenePath, String outputPath) {
        // 定义所有需要手动释放的 Mat 对象，防止内存泄漏
        Mat imgTemplate = null;
        Mat imgScene = null;
        Mat imgTemplateGray = null;
        Mat imgSceneGray = null;
        Mat descriptorsTemplate = null;
        Mat descriptorsScene = null;
        MatOfKeyPoint keypointsTemplate = null;
        MatOfKeyPoint keypointsScene = null;
        Mat hMatrix = null;
        Mat resultImg = null;

        try {
            // 1. 图片加载与校验
            // 读取原图用于最终画图
            imgTemplate = Imgcodecs.imread(templatePath); 
            imgScene = Imgcodecs.imread(scenePath);
            
            // 读取灰度图用于特征提取 (速度更快，精度通常足够)
            imgTemplateGray = Imgcodecs.imread(templatePath, Imgcodecs.IMREAD_GRAYSCALE);
            imgSceneGray = Imgcodecs.imread(scenePath, Imgcodecs.IMREAD_GRAYSCALE);

            if (imgTemplate.empty() || imgScene.empty()) {
                return new DetectionResult(false, "图片文件读取失败或路径不存在");
            }

            // 2. 初始化特征检测器 (SIFT 通常比 ORB 更稳健，适合工业工件)
            // 注意：如果你的 OpenCV 版本低于 4.4 且未编译 Contrib，SIFT 可能不可用，请改用 ORB.create()
            Feature2D detector = SIFT.create(); 

            keypointsTemplate = new MatOfKeyPoint();
            keypointsScene = new MatOfKeyPoint();
            descriptorsTemplate = new Mat();
            descriptorsScene = new Mat();

            // 3. 检测特征点并计算描述子
            detector.detectAndCompute(imgTemplateGray, new Mat(), keypointsTemplate, descriptorsTemplate);
            detector.detectAndCompute(imgSceneGray, new Mat(), keypointsScene, descriptorsScene);

            if (descriptorsTemplate.empty() || descriptorsScene.empty()) {
                return new DetectionResult(false, "无法提取特征点，图片可能过于模糊或无纹理");
            }

            // 4. 特征匹配 (使用 FLANN 匹配器，速度快于暴力匹配)
            // SIFT/SURF 描述子是浮点型，建议用 FLANN；如果是 ORB (二进制)，建议用 BRUTEFORCE_HAMMING
            DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
            List<MatOfDMatch> knnMatches = new ArrayList<>();
            
            // 必须检查描述子类型，防止类型不匹配导致的崩溃
            if (descriptorsTemplate.type() != CvType.CV_32F) {
                descriptorsTemplate.convertTo(descriptorsTemplate, CvType.CV_32F);
            }
            if (descriptorsScene.type() != CvType.CV_32F) {
                descriptorsScene.convertTo(descriptorsScene, CvType.CV_32F);
            }

            matcher.knnMatch(descriptorsTemplate, descriptorsScene, knnMatches, 2);

            // 5. Lowe's Ratio Test (筛选高质量匹配)
            // 阈值 0.7 是经验值，越小越严格
            float ratioThresh = 0.7f;
            List<DMatch> listOfGoodMatches = new ArrayList<>();
            for (MatOfDMatch match : knnMatches) {
                DMatch[] dmatches = match.toArray();
                if (dmatches.length >= 2 && dmatches[0].distance < ratioThresh * dmatches[1].distance) {
                    listOfGoodMatches.add(dmatches[0]);
                }
            }

            // 6. 校验匹配数量
            // 至少需要 4 个点才能计算透视变换 (Homography)
            if (listOfGoodMatches.size() < 4) {
                return new DetectionResult(false, "匹配点不足 (" + listOfGoodMatches.size() + "<4)，无法定位工件");
            }

            // 7. 计算单应性矩阵 (Homography)
            List<Point> objPoints = new ArrayList<>();
            List<Point> scenePoints = new ArrayList<>();
            List<KeyPoint> listOfKeypointsTemplate = keypointsTemplate.toList();
            List<KeyPoint> listOfKeypointsScene = keypointsScene.toList();

            for (DMatch goodMatch : listOfGoodMatches) {
                objPoints.add(listOfKeypointsTemplate.get(goodMatch.queryIdx).pt);
                scenePoints.add(listOfKeypointsScene.get(goodMatch.trainIdx).pt);
            }

            MatOfPoint2f objMat = new MatOfPoint2f();
            objMat.fromList(objPoints);
            MatOfPoint2f sceneMat = new MatOfPoint2f();
            sceneMat.fromList(scenePoints);

            // 使用 RANSAC 剔除误匹配点，阈值 5.0
            hMatrix = Calib3d.findHomography(objMat, sceneMat, Calib3d.RANSAC, 5.0);

            if (hMatrix.empty()) {
                return new DetectionResult(false, "无法计算空间变换矩阵");
            }

            // 8. 坐标映射 (获取工件在场景图中的四个角)
            Mat objCorners = new Mat(4, 1, CvType.CV_32FC2);
            Mat sceneCorners = new Mat(4, 1, CvType.CV_32FC2);

            objCorners.put(0, 0, new double[]{0, 0});
            objCorners.put(1, 0, new double[]{imgTemplate.cols(), 0});
            objCorners.put(2, 0, new double[]{imgTemplate.cols(), imgTemplate.rows()});
            objCorners.put(3, 0, new double[]{0, imgTemplate.rows()});

            Core.perspectiveTransform(objCorners, sceneCorners, hMatrix);

            // 9. 绘图 (在原图上画出多边形)
            resultImg = imgScene.clone();
            Point[] corners = new Point[4];
            for(int i=0; i<4; i++) {
                corners[i] = new Point(sceneCorners.get(i, 0));
            }

            // 绘制绿色边框，线宽 4
            Imgproc.line(resultImg, corners[0], corners[1], new Scalar(0, 255, 0), 4);
            Imgproc.line(resultImg, corners[1], corners[2], new Scalar(0, 255, 0), 4);
            Imgproc.line(resultImg, corners[2], corners[3], new Scalar(0, 255, 0), 4);
            Imgproc.line(resultImg, corners[3], corners[0], new Scalar(0, 255, 0), 4);

            // 保存图片到磁盘
            if (outputPath != null) {
                Imgcodecs.imwrite(outputPath, resultImg);
            }

            // 构造成功结果
            DetectionResult result = new DetectionResult(true, "检测成功");
            result.matchedCount = listOfGoodMatches.size();
            result.corners = corners;
            result.resultImage = resultImg; // 注意：调用者需要负责释放这个 Mat

            // 释放中间过程的 Mat (resultImg 交给外部释放，这里不释放)
            objCorners.release();
            sceneCorners.release();
            objMat.release();
            sceneMat.release();

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return new DetectionResult(false, "发生异常: " + e.getMessage());
        } finally {
            // 10. 统一资源释放 (非常重要！防止内存泄漏)
            safeRelease(imgTemplate);
            safeRelease(imgScene);
            safeRelease(imgTemplateGray);
            safeRelease(imgSceneGray);
            safeRelease(descriptorsTemplate);
            safeRelease(descriptorsScene);
            safeRelease(keypointsTemplate);
            safeRelease(keypointsScene);
            safeRelease(hMatrix);
            // 注意：resultImg 没有在这里释放，因为它被返回出去了
        }
    }

    /**
     * 安全释放 Mat 资源的辅助方法
     */
    private void safeRelease(Mat mat) {
        if (mat != null) {
            mat.release();
        }
    }

    // --- 测试入口 ---
    public static void main(String[] args) {
        IndustrialObjectDetector detector = new IndustrialObjectDetector();
        
        System.out.println("开始检测工件...");
        String template = "/Volumes/macEx/Temp/模板.png"; // 替换你的路径
        String scene = "/Volumes/macEx/训练/小件cx7564001/images/test/Image_20251229152344433.bmp";   // 替换你的路径
        String output = "/Volumes/macEx/Temp/result_detect.jpg";

        DetectionResult result = detector.detectObject(template, scene, output);

        if (result.success) {
            System.out.println("检测成功！");
            System.out.println("匹配特征点数: " + result.matchedCount);
            System.out.println("结果已保存至: " + output);
            // 只有在这里使用完后，才释放返回的图片内存
            if (result.resultImage != null) result.resultImage.release();
        } else {
            System.err.println("检测失败: " + result.message);
        }
        ObjectDetectionUtil.DetectionResult detectionResult = new ObjectDetectionUtil.DetectionResult(true, "检测成功");
        detectionResult.matchedCount = result.matchedCount;
        detectionResult.corners = new double[4][2];
        for (int i = 0; i < 4; i++) {
            detectionResult.corners[i][0] = result.corners[i].x;
            detectionResult.corners[i][1] = result.corners[i].y;
        }
        Mat mat = detector.cropImageByCorners(output, detectionResult.corners, "/Volumes/macEx/Temp/result_detect_crop.jpg", 0);
        mat.release();
    }

    public Mat cropImageByCorners(String imagePath, double[][] corners, String outputPath, int padding) {

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


        // 保存（如果指定了输出路径）- 使用 PNG 无损格式
        if (outputPath != null && !outputPath.isEmpty()) {
            // 根据输出路径扩展名选择格式
            String ext = outputPath.toLowerCase();
            boolean isPng = ext.endsWith(".png");

            if (isPng) {
                // PNG 无损压缩
                int[] params = new int[]{Imgcodecs.IMWRITE_PNG_COMPRESSION, 3}; // 0-9, 3 是平衡速度和压缩率
                Imgcodecs.imwrite(outputPath, cropped, new MatOfInt(params));
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
                } catch (Exception e) {
                    Imgcodecs.imwrite(outputPath, cropped);
                }
            }
        }

        fullImage.release();
        return cropped;
    }
}