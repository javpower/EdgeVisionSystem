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
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 适配模型: BoundingBox(min/max), DetectedObject, Template
 * 优化记录: 引入 SIFT 特征缓存 + 场景图降采样以提升性能
 */
public class VisionTool {

    // 算法参数
    private static final double RANSAC_THRESH = 5.0;
    private static final float MATCH_RATIO = 0.7f;
    private static final int MIN_MATCH_COUNT = 4;
    private static final double MATCH_DISTANCE_THRESHOLD = 50.0;

    // --- 性能优化参数 ---
    // 处理时的最大宽度。将4K/2K大图缩小到此宽度进行计算，速度可提升10-20倍。
    private static final int PROCESS_WIDTH = 1280;

    // --- 缓存层 ---
    // Key: templateId, Value: 特征数据
    private static final Map<String, TemplateCacheData> templateCache = new ConcurrentHashMap<>();

    // 内部类：缓存数据结构
    private static class TemplateCacheData {
        MatOfKeyPoint keyPoints;
        Mat descriptors;

        public TemplateCacheData(MatOfKeyPoint keyPoints, Mat descriptors) {
            this.keyPoints = keyPoints;
            this.descriptors = descriptors;
        }
    }

    // =========================================================
    // 1. 建模方法 (Create Template)
    // =========================================================
    public static Template createTemplate(String base64Origin, int[] cropRect, List<DetectedObject> features, String templateId) throws IOException {
        // ... (保持原有逻辑不变) ...
        Mat original = base64ToMat(base64Origin);
        if (original == null || original.empty()) throw new IllegalArgumentException("Invalid Base64 Image");

        Mat cropped = null;
        try {
            Rect rect = new Rect(cropRect[0], cropRect[1], cropRect[2], cropRect[3]);
            rect.x = Math.max(0, rect.x);
            rect.y = Math.max(0, rect.y);
            if (rect.x + rect.width > original.cols()) rect.width = original.cols() - rect.x;
            if (rect.y + rect.height > original.rows()) rect.height = original.rows() - rect.y;

            cropped = new Mat(original, rect);

            Path imagesDir = Paths.get("templates", "images");
            Files.createDirectories(imagesDir);
            String imageFileName = templateId + ".jpg";
            String templateImagePath = imagesDir.resolve(imageFileName).toString();

            Template template = new Template();
            template.setTemplateId(templateId);
            template.setCreatedAt(LocalDateTime.now());
            template.setToleranceX(5.0);
            template.setToleranceY(5.0);

            BoundingBox cropBox = new BoundingBox();
            cropBox.setMinX(rect.x);
            cropBox.setMinY(rect.y);
            cropBox.setMaxX(rect.x + rect.width);
            cropBox.setMaxY(rect.y + rect.height);
            template.setBoundingBox(cropBox);

            List<TemplateFeature> templateFeatures = new ArrayList<>();
            for (DetectedObject obj : features) {
                TemplateFeature tf = new TemplateFeature();
                tf.setId(UUID.randomUUID().toString());
                tf.setName(obj.getClassName());
                tf.setClassId(obj.getClassId());
                tf.setPosition(obj.getCenter());

                double relX = obj.getCenter().x - rect.x;
                double relY = obj.getCenter().y - rect.y;
                tf.setRelativePosition(new Point(relX, relY));

                TemplateFeature.BoundingBox featBox = new TemplateFeature.BoundingBox();
                featBox.setX(obj.getCenter().x);
                featBox.setY(obj.getCenter().y);
                featBox.setHeight(obj.getHeight());
                featBox.setWidth(obj.getWidth());

                tf.setBbox(featBox);
                tf.setRequired(true);
                templateFeatures.add(tf);
            }
            template.setFeatures(templateFeatures);

            Imgcodecs.imwrite(templateImagePath, cropped);
            template.setImagePath(templateImagePath);

            ImageSize size = new ImageSize();
            size.setWidth(cropped.cols());
            size.setHeight(cropped.rows());
            template.setImageSize(size);

            // 建模时顺便清除旧缓存（如果有）
            templateCache.remove(templateId);

            return template;

        } finally {
            if (original != null) original.release();
            if (cropped != null) cropped.release();
        }
    }

    // =========================================================
    // 2. 计算坐标方法 (Calculate Coordinates - OPTIMIZED)
    // =========================================================
    /**
     * 基于模板计算新图中的坐标 (SIFT + Homography + Downsampling + Caching)
     * @param template 模板对象
     * @param sceneMat 场景图 Mat（调用方负责释放）
     * @return 检测对象列表
     */
    public static List<DetectedObject> calculateTemplateCoordinates(Template template, Mat sceneMat) {
        Mat imgScene = null;
        Mat imgSceneResized = null; // 用于存储缩小后的图
        Mat hMatrix = null;

        // 临时变量，需要在 finally 中释放
        MatOfKeyPoint kpScene = null;
        Mat descScene = null;
        MatOfPoint2f matPtsTpl = null;
        MatOfPoint2f matPtsScene = null;
        MatOfPoint2f srcMat = null;
        MatOfPoint2f dstMat = null;

        try {
            // --- 优化点 1: 从缓存获取模板特征 (0ms) ---
            TemplateCacheData tplData = getOrComputeTemplateFeatures(template);
            if (tplData == null || tplData.descriptors.empty()) return new ArrayList<>();

            // 2. 准备场景图（转灰度图）
            if (sceneMat.channels() > 1) {
                imgScene = new Mat();
                Imgproc.cvtColor(sceneMat, imgScene, Imgproc.COLOR_BGR2GRAY);
            } else {
                imgScene = sceneMat; // 引用传递，不释放
            }

            if (imgScene.empty()) return new ArrayList<>();

            // --- 优化点 2: 图像降采样 (Downsampling) ---
            double scaleFactor = 1.0;
            int originalWidth = imgScene.cols();

            // 如果原图太宽，进行缩小处理
            if (originalWidth > PROCESS_WIDTH) {
                scaleFactor = (double) PROCESS_WIDTH / originalWidth;
                imgSceneResized = new Mat();
                // INTER_AREA 插值最适合缩小图像，保留特征
                Imgproc.resize(imgScene, imgSceneResized, new Size(), scaleFactor, scaleFactor, Imgproc.INTER_AREA);
            } else {
                imgSceneResized = imgScene; // 引用传递，无需释放
            }

            // 3. SIFT 特征提取 (在小图上运行，速度极大提升)
            Feature2D detector = SIFT.create();
            kpScene = new MatOfKeyPoint();
            descScene = new Mat();
            detector.detectAndCompute(imgSceneResized, new Mat(), kpScene, descScene);

            if (descScene.empty()) return new ArrayList<>();

            // 4. FLANN 匹配
            DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
            List<MatOfDMatch> knnMatches = new ArrayList<>();

            // 确保类型一致 (SIFT 默认是 CV_32F，但也做防御性转换)
            if (tplData.descriptors.type() != CvType.CV_32F) tplData.descriptors.convertTo(tplData.descriptors, CvType.CV_32F);
            if (descScene.type() != CvType.CV_32F) descScene.convertTo(descScene, CvType.CV_32F);

            matcher.knnMatch(tplData.descriptors, descScene, knnMatches, 2);

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
            List<KeyPoint> kpTplList = tplData.keyPoints.toList();
            List<KeyPoint> kpSceneList = kpScene.toList();

            for (DMatch m : goodMatches) {
                ptsTpl.add(kpTplList.get(m.queryIdx).pt);
                ptsScene.add(kpSceneList.get(m.trainIdx).pt);
            }

            matPtsTpl = new MatOfPoint2f(); matPtsTpl.fromList(ptsTpl);
            matPtsScene = new MatOfPoint2f(); matPtsScene.fromList(ptsScene);
            hMatrix = Calib3d.findHomography(matPtsTpl, matPtsScene, Calib3d.RANSAC, RANSAC_THRESH);

            if (hMatrix.empty()) return new ArrayList<>();

            // 7. 坐标透视变换
            List<org.opencv.core.Point> srcPoints = new ArrayList<>();
            List<TemplateFeature> features = template.getFeatures();

            for (TemplateFeature tf : features) {
                srcPoints.add(new org.opencv.core.Point(tf.getRelativePosition().x, tf.getRelativePosition().y));
            }

            srcMat = new MatOfPoint2f(); srcMat.fromList(srcPoints);
            dstMat = new MatOfPoint2f();
            Core.perspectiveTransform(srcMat, dstMat, hMatrix);
            List<org.opencv.core.Point> dstList = dstMat.toList();

            // 8. 封装返回结果 (关键：坐标还原)
            List<DetectedObject> result = new ArrayList<>();
            for (int i = 0; i < features.size(); i++) {
                TemplateFeature tf = features.get(i);
                org.opencv.core.Point p = dstList.get(i);

                DetectedObject obj = new DetectedObject();
                obj.setClassName(tf.getName());
                obj.setClassId(tf.getClassId());

                // --- 坐标映射回原图尺寸 ---
                // 我们是在 scaleFactor 的图上算的，所以结果要除以 scaleFactor 变回去
                double realX = p.x / scaleFactor;
                double realY = p.y / scaleFactor;
                obj.setCenter(new Point(realX, realY));

                // 宽度高度暂时使用模板的原始宽高
                if (tf.getBbox() != null) {
                    obj.setWidth(tf.getBbox().getWidth());
                    obj.setHeight(tf.getBbox().getHeight());
                } else {
                    obj.setWidth(0); obj.setHeight(0);
                }
                obj.setConfidence(1.0);
                result.add(obj);
            }
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        } finally {
            // 资源释放 (注意不要释放缓存中的 tplData 和传入的 sceneMat)
            if (imgScene != null && imgScene != sceneMat) imgScene.release();
            // 只有当 imgSceneResized 是独立创建的对象时才释放
            if (imgSceneResized != null && imgSceneResized != imgScene && imgSceneResized != sceneMat) imgSceneResized.release();
            if (hMatrix != null) hMatrix.release();
            if (kpScene != null) kpScene.release();
            if (descScene != null) descScene.release();
            if (matPtsTpl != null) matPtsTpl.release();
            if (matPtsScene != null) matPtsScene.release();
            if (srcMat != null) srcMat.release();
            if (dstMat != null) dstMat.release();
        }
    }

    /**
     * 基于模板计算新图中的坐标 (SIFT + Homography + Downsampling + Caching)
     * @param template 模板对象
     * @param base64Scene 场景图 base64 字符串
     * @return 检测对象列表
     */
    public static List<DetectedObject> calculateTemplateCoordinates(Template template, String base64Scene) {
        Mat sceneMat = base64ToMat(base64Scene, Imgcodecs.IMREAD_COLOR);
        if (sceneMat == null || sceneMat.empty()) {
            return new ArrayList<>();
        }
        try {
            return calculateTemplateCoordinates(template, sceneMat);
        } finally {
            sceneMat.release();
        }
    }

    /**
     * 辅助方法：从缓存获取模板特征，如果缓存不存在则计算并存入
     * (线程安全)
     */
    private static TemplateCacheData getOrComputeTemplateFeatures(Template template) {
        String tplId = template.getTemplateId();

        return templateCache.computeIfAbsent(tplId, k -> {
            Mat imgTpl = null;
            try {
                // 读取模板图片
                imgTpl = Imgcodecs.imread(template.getImagePath(), Imgcodecs.IMREAD_GRAYSCALE);
                if (imgTpl == null || imgTpl.empty()) return null;

                // 计算 SIFT 特征 (只做一次)
                Feature2D detector = SIFT.create();
                MatOfKeyPoint kpTpl = new MatOfKeyPoint();
                Mat descTpl = new Mat();
                detector.detectAndCompute(imgTpl, new Mat(), kpTpl, descTpl);

                if (descTpl.empty()) return null;

                // 返回数据 (KeyPoints 和 Descriptors 将驻留内存)
                return new TemplateCacheData(kpTpl, descTpl);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            } finally {
                // 释放图片像素数据，保留特征数据
                if (imgTpl != null) imgTpl.release();
            }
        });
    }

    /**
     * 手动清理缓存 (例如更新模板时调用)
     */
    public static void clearCache(String templateId) {
        TemplateCacheData data = templateCache.remove(templateId);
        if (data != null) {
            if (data.descriptors != null) data.descriptors.release();
            if (data.keyPoints != null) data.keyPoints.release();
        }
    }

    // =========================================================
    // 3. 比对方法 (Compare Results) - 保持不变
    // =========================================================
    public static List<QualityStandardService.QualityEvaluationResult.TemplateComparison> compareResults(
            List<DetectedObject> templateObjs,
            List<DetectedObject> yoloObjs,double defaultToleranceX,double defaultToleranceY) {

        // ... (保持原代码逻辑不变) ...
        List<QualityStandardService.QualityEvaluationResult.TemplateComparison> results = new ArrayList<>();
        boolean[] yoloMatched = new boolean[yoloObjs.size()];

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

            for (int i = 0; i < yoloObjs.size(); i++) {
                if (yoloMatched[i]) continue;
                DetectedObject yObj = yoloObjs.get(i);
                if (!tObj.getClassName().equalsIgnoreCase(yObj.getClassName())) continue;

                double dx = Math.abs(tObj.getCenter().x - yObj.getCenter().x);
                double dy = Math.abs(tObj.getCenter().y - yObj.getCenter().y);
                double totalErr = Math.sqrt(dx * dx + dy * dy);

                if (totalErr < MATCH_DISTANCE_THRESHOLD && totalErr < minTotalErr) {
                    minTotalErr = totalErr;
                    bestMatch = yObj;
                    bestMatchIdx = i;
                }
            }

            if (bestMatch != null) {
                yoloMatched[bestMatchIdx] = true;
                double xErr = Math.abs(tObj.getCenter().x - bestMatch.getCenter().x);
                double yErr = Math.abs(tObj.getCenter().y - bestMatch.getCenter().y);

                comp.setDetectedPosition(bestMatch.getCenter());
                comp.setXError(xErr);
                comp.setYError(yErr);
                comp.setTotalError(minTotalErr);

                if (xErr <= defaultToleranceX && yErr <= defaultToleranceY) {
                    comp.setStatus(FeatureComparison.ComparisonStatus.PASSED);
                    comp.setWithinTolerance(true);
                } else {
                    comp.setStatus(FeatureComparison.ComparisonStatus.DEVIATION_EXCEEDED);
                    comp.setWithinTolerance(false);
                }
            } else {
                comp.setStatus(FeatureComparison.ComparisonStatus.MISSING);
                comp.setDetectedPosition(tObj.getCenter());
                comp.setWithinTolerance(false);
            }
            results.add(comp);
        }

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
    private static Mat base64ToMat(String base64) {
        return base64ToMat(base64, Imgcodecs.IMREAD_COLOR);
    }

    private static Mat base64ToMat(String base64, int flags) {
        try {
            if (base64 == null) return null;
            if (base64.contains(",")) base64 = base64.split(",")[1];
            byte[] data = Base64.getDecoder().decode(base64);
            MatOfByte mob = new MatOfByte(data);
            return Imgcodecs.imdecode(mob, flags);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}