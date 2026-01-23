package com.edge.vision.util;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.*;
import com.edge.vision.service.QualityStandardService;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.Feature2D;
import org.opencv.features2d.SIFT;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 适配模型: BoundingBox(min/max), DetectedObject, Template
 */
public class VisionTool {

    // 算法参数
    private static final double RANSAC_THRESH = 5.0;
    private static final float MATCH_RATIO = 0.7f;
    private static final int MIN_MATCH_COUNT = 4;
    private static final double MATCH_DISTANCE_THRESHOLD = 50.0;

    // =========================================================
    // 1. 建模方法 (Create Template)
    // =========================================================
    /**
     * 创建并保存模板
     *
     * @param base64Origin 原图 Base64 字符串
     * @param cropRect     裁剪框数组 [x, y, w, h] (相对于原图)
     * @param features     标注的特征列表 (DetectedObject中的坐标基于原图)
     * @return 模板 JSON 文件的绝对路径
     */
    public static Template createTemplate(String base64Origin, int[] cropRect, List<DetectedObject> features,String templateId) throws IOException {
        Mat original = base64ToMat(base64Origin);
        if (original == null || original.empty()) throw new IllegalArgumentException("Invalid Base64 Image");

        Mat cropped = null;
        try {
            // 1. 处理裁剪区域 (边界检查)
            Rect rect = new Rect(cropRect[0], cropRect[1], cropRect[2], cropRect[3]);
            rect.x = Math.max(0, rect.x);
            rect.y = Math.max(0, rect.y);
            if (rect.x + rect.width > original.cols()) rect.width = original.cols() - rect.x;
            if (rect.y + rect.height > original.rows()) rect.height = original.rows() - rect.y;

            // 2. 执行裁剪
            cropped = new Mat(original, rect);

            // 3. 准备存储
            Path imagesDir = Paths.get("templates", "images");
            Files.createDirectories(imagesDir);
            String imageFileName = templateId + ".jpg";
            String templateImagePath = imagesDir.resolve(imageFileName).toString();

            // 4. 构建 Template 对象
            Template template = new Template();
            template.setTemplateId(templateId);
            template.setCreatedAt(LocalDateTime.now());
            // 设置默认全局容差
            template.setToleranceX(5.0);
            template.setToleranceY(5.0);

            // 设置模板图的 BoundingBox (min/max 格式)
            BoundingBox cropBox = new BoundingBox();
            cropBox.setMinX(rect.x);
            cropBox.setMinY(rect.y);
            cropBox.setMaxX(rect.x + rect.width);
            cropBox.setMaxY(rect.y + rect.height);
            template.setBoundingBox(cropBox);

            // 5. 转换特征点 (绝对坐标 -> 相对坐标)
            List<TemplateFeature> templateFeatures = new ArrayList<>();
            for (DetectedObject obj : features) {
                TemplateFeature tf = new TemplateFeature();
                tf.setId(UUID.randomUUID().toString());
                tf.setName(obj.getClassName());
                tf.setClassId(obj.getClassId());

                // 记录绝对坐标
                tf.setPosition(obj.getCenter());

                // 计算相对坐标 (相对于裁剪图左上角) -> SIFT 匹配用
                double relX = obj.getCenter().x - rect.x;
                double relY = obj.getCenter().y - rect.y;
                tf.setRelativePosition(new Point(relX, relY));

                // 转换 BBox (Center/Size)
                TemplateFeature.BoundingBox featBox = new TemplateFeature.BoundingBox();
                featBox.setX(obj.getCenter().x);
                featBox.setY(obj.getCenter().y);
                featBox.setHeight(obj.getHeight());
                featBox.setWidth(obj.getWidth());

                tf.setBbox(featBox);
                tf.setRequired(true); // 默认必须检测

                templateFeatures.add(tf);
            }
            template.setFeatures(templateFeatures);

            // 6. 保存裁剪后的图片
            Imgcodecs.imwrite(templateImagePath, cropped);   // <-- 就是这一行
            template.setImagePath(templateImagePath);

            // 设置图片尺寸元数据
            ImageSize size = new ImageSize();
            size.setWidth(cropped.cols());
            size.setHeight(cropped.rows());
            template.setImageSize(size);
            return template;

        } finally {
            if (original != null) original.release();
            if (cropped != null) cropped.release();
        }
    }

    // =========================================================
    // 2. 计算坐标方法 (Calculate Coordinates)
    // =========================================================
    /**
     * 基于模板计算新图中的坐标 (SIFT + Homography)
     *
     * @param base64Scene      新场景图 Base64
     * @return 预测的特征列表 (DetectedObject格式)
     */
    public static List<DetectedObject> calculateTemplateCoordinates(Template template, String base64Scene) {
        Mat imgTpl = null;
        Mat imgScene = null;
        Mat hMatrix = null;

        try {
            String tplImgPath = template.getImagePath();
            // 2. 加载图片 (灰度图用于计算)
            imgTpl = Imgcodecs.imread(tplImgPath, Imgcodecs.IMREAD_GRAYSCALE);
            imgScene = base64ToMat(base64Scene, Imgcodecs.IMREAD_GRAYSCALE);

            if (imgTpl.empty() || imgScene.empty()) return new ArrayList<>();

            // 3. SIFT 特征提取
            Feature2D detector = SIFT.create();
            MatOfKeyPoint kpTpl = new MatOfKeyPoint();
            MatOfKeyPoint kpScene = new MatOfKeyPoint();
            Mat descTpl = new Mat();
            Mat descScene = new Mat();

            detector.detectAndCompute(imgTpl, new Mat(), kpTpl, descTpl);
            detector.detectAndCompute(imgScene, new Mat(), kpScene, descScene);

            if (descTpl.empty() || descScene.empty()) return new ArrayList<>();

            // 4. FLANN 匹配
            DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
            List<MatOfDMatch> knnMatches = new ArrayList<>();

            // 转换数据类型为 CV_32F
            if (descTpl.type() != CvType.CV_32F) descTpl.convertTo(descTpl, CvType.CV_32F);
            if (descScene.type() != CvType.CV_32F) descScene.convertTo(descScene, CvType.CV_32F);

            matcher.knnMatch(descTpl, descScene, knnMatches, 2);

            // 5. Ratio Test 筛选
            List<DMatch> goodMatches = new ArrayList<>();
            for (MatOfDMatch m : knnMatches) {
                DMatch[] dm = m.toArray();
                if (dm.length >= 2 && dm[0].distance < MATCH_RATIO * dm[1].distance) {
                    goodMatches.add(dm[0]);
                }
            }
            if (goodMatches.size() < MIN_MATCH_COUNT) return new ArrayList<>();

            // 6. 计算单应性矩阵 (Homography)
            List<org.opencv.core.Point> ptsTpl = new ArrayList<>();
            List<org.opencv.core.Point> ptsScene = new ArrayList<>();
            List<KeyPoint> kpTplList = kpTpl.toList();
            List<KeyPoint> kpSceneList = kpScene.toList();

            for (DMatch m : goodMatches) {
                ptsTpl.add(kpTplList.get(m.queryIdx).pt);
                ptsScene.add(kpSceneList.get(m.trainIdx).pt);
            }

            MatOfPoint2f matPtsTpl = new MatOfPoint2f(); matPtsTpl.fromList(ptsTpl);
            MatOfPoint2f matPtsScene = new MatOfPoint2f(); matPtsScene.fromList(ptsScene);
            hMatrix = Calib3d.findHomography(matPtsTpl, matPtsScene, Calib3d.RANSAC, RANSAC_THRESH);

            if (hMatrix.empty()) return new ArrayList<>();

            // 7. 坐标透视变换
            List<org.opencv.core.Point> srcPoints = new ArrayList<>();
            List<TemplateFeature> features = template.getFeatures();

            // 使用 relativePosition (基于小图的坐标)
            for (TemplateFeature tf : features) {
                srcPoints.add(new org.opencv.core.Point(tf.getRelativePosition().x, tf.getRelativePosition().y));
            }

            MatOfPoint2f srcMat = new MatOfPoint2f(); srcMat.fromList(srcPoints);
            MatOfPoint2f dstMat = new MatOfPoint2f();
            Core.perspectiveTransform(srcMat, dstMat, hMatrix);
            List<org.opencv.core.Point> dstList = dstMat.toList();

            // 8. 封装返回结果
            List<DetectedObject> result = new ArrayList<>();
            for (int i = 0; i < features.size(); i++) {
                TemplateFeature tf = features.get(i);
                org.opencv.core.Point p = dstList.get(i);

                DetectedObject obj = new DetectedObject();
                // 关键：将 className 设置为 FeatureName，用于后续 ID 匹配
                obj.setClassName(tf.getName());
                obj.setClassId(tf.getClassId());
                obj.setCenter(new Point(p.x, p.y));

                // 宽度高度暂时使用模板的原始宽高
                if (tf.getBbox() != null) {
                    obj.setWidth(tf.getBbox().getWidth());
                    obj.setHeight(tf.getBbox().getHeight());
                } else {
                    obj.setWidth(0); obj.setHeight(0);
                }
                obj.setConfidence(1.0); // 模板推算的，置信度为 1
                result.add(obj);
            }
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        } finally {
            if (imgTpl != null) imgTpl.release();
            if (imgScene != null) imgScene.release();
            if (hMatrix != null) hMatrix.release();
        }
    }

    // =========================================================
    // 3. 比对方法 (Compare Results)
    // =========================================================
    /**
     * 比对逻辑：模板计算结果 vs YOLO 实际检测结果
     *
     * @param templateObjs 模板计算出的理论坐标 (Expected)
     * @param yoloObjs     YOLO 实际识别出的坐标 (Actual)
     * @return 详细的比对结果
     */
    public static List<QualityStandardService.QualityEvaluationResult.TemplateComparison> compareResults(
            List<DetectedObject> templateObjs,
            List<DetectedObject> yoloObjs,double defaultToleranceX,double defaultToleranceY) {
        List<QualityStandardService.QualityEvaluationResult.TemplateComparison> results = new ArrayList<>();
        boolean[] yoloMatched = new boolean[yoloObjs.size()];

        // --- 1. 遍历模板预期点 (Template Objects) ---
        for (DetectedObject tObj : templateObjs) {
            QualityStandardService.QualityEvaluationResult.TemplateComparison comp = new QualityStandardService.QualityEvaluationResult.TemplateComparison();

            comp.setFeatureName(tObj.getClassName());
            comp.setClassName(tObj.getClassName());
            comp.setClassId(tObj.getClassId());
            comp.setTemplatePosition(tObj.getCenter());
            comp.setToleranceX(defaultToleranceX);
            comp.setToleranceY(defaultToleranceY);

            DetectedObject bestMatch = null;
            int bestMatchIdx = -1;
            double minTotalErr = Double.MAX_VALUE;

            // 在 YOLO 结果中寻找最近邻
            for (int i = 0; i < yoloObjs.size(); i++) {
                if (yoloMatched[i]) continue;
                DetectedObject yObj = yoloObjs.get(i);

                // 类别校验 (忽略大小写)
                if (!tObj.getClassName().equalsIgnoreCase(yObj.getClassName())) continue;

                // 计算欧氏距离
                double dx = Math.abs(tObj.getCenter().x - yObj.getCenter().x);
                double dy = Math.abs(tObj.getCenter().y - yObj.getCenter().y);
                double totalErr = Math.sqrt(dx * dx + dy * dy);

                // 距离阈值筛选
                if (totalErr < MATCH_DISTANCE_THRESHOLD && totalErr < minTotalErr) {
                    minTotalErr = totalErr;
                    bestMatch = yObj;
                    bestMatchIdx = i;
                }
            }

            if (bestMatch != null) {
                // 匹配成功
                yoloMatched[bestMatchIdx] = true;
                double xErr = Math.abs(tObj.getCenter().x - bestMatch.getCenter().x);
                double yErr = Math.abs(tObj.getCenter().y - bestMatch.getCenter().y);

                comp.setDetectedPosition(bestMatch.getCenter());
                comp.setXError(xErr);
                comp.setYError(yErr);
                comp.setTotalError(minTotalErr);

                // 判断是否在容差内
                if (xErr <= defaultToleranceX && yErr <= defaultToleranceY) {
                    comp.setStatus(FeatureComparison.ComparisonStatus.PASSED);
                    comp.setWithinTolerance(true);
                } else {
                    comp.setStatus(FeatureComparison.ComparisonStatus.DEVIATION_EXCEEDED);
                    comp.setWithinTolerance(false);
                }
            } else {
                // 漏检 (Missing)
                comp.setStatus(FeatureComparison.ComparisonStatus.MISSING);
                comp.setDetectedPosition(tObj.getCenter());
                comp.setWithinTolerance(false);
            }
            results.add(comp);
        }

        // --- 2. 检查多余的 YOLO 对象 (Extra) ---
        for (int i = 0; i < yoloObjs.size(); i++) {
            if (!yoloMatched[i]) {
                DetectedObject extraObj = yoloObjs.get(i);
                QualityStandardService.QualityEvaluationResult.TemplateComparison extraComp =
                        new QualityStandardService.QualityEvaluationResult.TemplateComparison();

                extraComp.setFeatureName(extraObj.getClassName());
                extraComp.setClassName(extraObj.getClassName());
                extraComp.setClassId(extraObj.getClassId());
                extraComp.setDetectedPosition(extraObj.getCenter());
                extraComp.setStatus(FeatureComparison.ComparisonStatus.EXTRA);
                extraComp.setWithinTolerance(false);

                results.add(extraComp);
            }
        }

        return results;
    }

    // =========================================================
    // 辅助工具方法
    // =========================================================

    /**
     * Base64 字符串转 OpenCV Mat
     */
    private static Mat base64ToMat(String base64) {
        return base64ToMat(base64, Imgcodecs.IMREAD_COLOR);
    }

    private static Mat base64ToMat(String base64, int flags) {
        try {
            if (base64 == null) return null;
            if (base64.contains(",")) base64 = base64.split(",")[1]; // 移除 data:image... 前缀

            byte[] data = Base64.getDecoder().decode(base64);
            MatOfByte mob = new MatOfByte(data);
            return Imgcodecs.imdecode(mob, flags);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}