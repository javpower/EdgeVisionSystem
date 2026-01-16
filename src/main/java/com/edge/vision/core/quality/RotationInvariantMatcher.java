package com.edge.vision.core.quality;

import com.edge.vision.core.template.model.AnchorPoint;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 旋转和平移不变的特征匹配器
 * <p>
 * 使用相对位置模式匹配，不需要显式估计旋转角度
 */
@Component
public class RotationInvariantMatcher {
    private static final Logger logger = LoggerFactory.getLogger(RotationInvariantMatcher.class);

    /**
     * 生成与模板锚点对应的检测锚点
     * <p>
     * 先用质心距离匹配找到对应特征，然后估计旋转角度
     */
    public List<AnchorPoint> generateDetectedAnchors(
        Template template,
        List<DetectedObject> detectedObjects
    ) {
        logger.info("生成检测锚点（基于质心距离匹配）");

        List<AnchorPoint> templateAnchors = template.getAnchorPoints();
        if (templateAnchors == null || templateAnchors.isEmpty()) {
            logger.warn("模板没有锚点，使用基础锚点");
            return generateBasicAnchors(detectedObjects);
        }

        if (detectedObjects.isEmpty()) {
            logger.warn("没有检测对象");
            return new ArrayList<>();
        }

        // 计算质心
        Point templateCentroid = calculateFeatureCentroid(template.getFeatures());
        Point detectedCentroid = calculateDetectedCentroid(detectedObjects);

        // 估计旋转角度（基于质心距离匹配）
        double rotationAngle = estimateRotationFromMatches(
            template.getFeatures(), detectedObjects, templateCentroid, detectedCentroid);

        // 应用变换生成检测锚点
        List<AnchorPoint> detectedAnchors = new ArrayList<>();
        for (AnchorPoint templateAnchor : templateAnchors) {
            Point templatePos = templateAnchor.getPosition();

            // 应用平移 + 旋转变换
            Point detectedPos = transformPoint(
                templatePos, templateCentroid, detectedCentroid, rotationAngle);

            AnchorPoint detectedAnchor = new AnchorPoint(
                templateAnchor.getId() + "_detected",
                templateAnchor.getType(),
                detectedPos,
                "检测_" + templateAnchor.getDescription()
            );
            detectedAnchors.add(detectedAnchor);
        }

        logger.info("生成了 {} 个检测锚点，旋转={}°, 质心偏移=({:.2f}, {:.2f})",
            detectedAnchors.size(),
            String.format("%.2f", Math.toDegrees(rotationAngle)),
            String.format("%.2f", detectedCentroid.x - templateCentroid.x),
            String.format("%.2f", detectedCentroid.y - templateCentroid.y));

        return detectedAnchors;
    }

    /**
     * 基于质心距离匹配估计旋转角度
     * <p>
     * 使用已匹配的特征对计算平均旋转角度
     */
    private double estimateRotationFromMatches(
        List<TemplateFeature> templateFeatures,
        List<DetectedObject> detectedObjects,
        Point templateCentroid,
        Point detectedCentroid
    ) {
        // 为每个模板特征找到距离质心距离最接近的检测对象
        double sumAngleDiff = 0;
        int matchCount = 0;
        double maxDistanceError = 50.0; // 距离误差阈值

        for (TemplateFeature feature : templateFeatures) {
            Point featurePos = feature.getPosition();
            double featureDist = distance(featurePos, templateCentroid);

            // 找到距离最接近的同类检测对象
            DetectedObject nearestMatch = null;
            double minError = maxDistanceError;

            for (DetectedObject obj : detectedObjects) {
                if (obj.getClassId() != feature.getClassId()) {
                    continue;
                }

                Point objPos = obj.getCenter();
                double objDist = distance(objPos, detectedCentroid);
                double error = Math.abs(featureDist - objDist);

                if (error < minError) {
                    minError = error;
                    nearestMatch = obj;
                }
            }

            if (nearestMatch != null) {
                // 计算角度差
                double featureAngle = Math.atan2(
                    featurePos.y - templateCentroid.y,
                    featurePos.x - templateCentroid.x
                );
                double detectedAngle = Math.atan2(
                    nearestMatch.getCenter().y - detectedCentroid.y,
                    nearestMatch.getCenter().x - detectedCentroid.x
                );

                double angleDiff = detectedAngle - featureAngle;
                // 归一化到 [-π, π]
                while (angleDiff > Math.PI) angleDiff -= 2 * Math.PI;
                while (angleDiff < -Math.PI) angleDiff += 2 * Math.PI;

                sumAngleDiff += angleDiff;
                matchCount++;

                if (matchCount >= 10) break; // 用前10个匹配就够了
            }
        }

        double avgRotation = matchCount > 0 ? sumAngleDiff / matchCount : 0;
        logger.debug("估计旋转角度: {}° (基于 {} 个匹配)",
            String.format("%.2f", Math.toDegrees(avgRotation)), matchCount);

        return avgRotation;
    }

    /**
     * 变换点：从模板坐标系到检测坐标系
     */
    private Point transformPoint(
        Point templatePoint,
        Point templateCentroid,
        Point detectedCentroid,
        double rotationAngle
    ) {
        // 相对于模板质心
        double dx = templatePoint.x - templateCentroid.x;
        double dy = templatePoint.y - templateCentroid.y;

        // 应用旋转
        double cos = Math.cos(rotationAngle);
        double sin = Math.sin(rotationAngle);

        double rotatedX = dx * cos - dy * sin;
        double rotatedY = dx * sin + dy * cos;

        // 加上检测质心
        return new Point(
            detectedCentroid.x + rotatedX,
            detectedCentroid.y + rotatedY
        );
    }

    /**
     * 计算两点之间的距离
     */
    private double distance(Point p1, Point p2) {
        return Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    /**
     * 计算模板特征的质心
     */
    private Point calculateFeatureCentroid(List<TemplateFeature> features) {
        if (features == null || features.isEmpty()) {
            return new Point(0, 0);
        }

        double sumX = 0, sumY = 0;
        for (TemplateFeature feature : features) {
            sumX += feature.getPosition().x;
            sumY += feature.getPosition().y;
        }
        return new Point(sumX / features.size(), sumY / features.size());
    }

    /**
     * 计算检测对象的质心
     */
    private Point calculateDetectedCentroid(List<DetectedObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return new Point(0, 0);
        }

        double sumX = 0, sumY = 0;
        for (DetectedObject obj : objects) {
            sumX += obj.getCenter().x;
            sumY += obj.getCenter().y;
        }
        return new Point(sumX / objects.size(), sumY / objects.size());
    }

    /**
     * 生成基础锚点（只有几何中心）
     */
    private List<AnchorPoint> generateBasicAnchors(List<DetectedObject> detectedObjects) {
        Point center = calculateDetectedCentroid(detectedObjects);
        List<AnchorPoint> anchors = new ArrayList<>();
        anchors.add(new AnchorPoint(
            "A0_detected",
            AnchorPoint.AnchorType.GEOMETRIC_CENTER,
            center,
            "检测几何中心"
        ));
        return anchors;
    }
}
