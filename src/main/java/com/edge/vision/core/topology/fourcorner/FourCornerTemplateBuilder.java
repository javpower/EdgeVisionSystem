package com.edge.vision.core.topology.fourcorner;

import com.edge.vision.core.template.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 四角模板构建器辅助类
 * <p>
 * 简化四角模板的创建过程
 */
public class FourCornerTemplateBuilder {
    private static final Logger logger = LoggerFactory.getLogger(FourCornerTemplateBuilder.class);

    private final FingerprintCalculator calculator = new FingerprintCalculator();

    /**
     * 从现有模板数据创建四角模板
     * <p>
     * 使用场景：
     * 1. 已有模板特征点数据
     * 2. 已标定工件四个角的坐标
     * 3. 自动计算所有特征点的指纹
     *
     * @param templateId     模板ID
     * @param corners        四个角坐标 [TL, TR, BR, BL]
     * @param features       特征点列表
     * @return 四角模板
     */
    public FourCornerTemplate buildFromFeatures(
            String templateId,
            Point[] corners,
            List<FeatureDefinition> features) {

        logger.info("Building FourCornerTemplate: id={}, corners={}, features={}",
            templateId, corners.length, features.size());

        FourCornerTemplate.Builder builder = new FourCornerTemplate.Builder()
            .templateId(templateId)
            .corners(corners);

        // 为每个特征点计算指纹
        for (FeatureDefinition feature : features) {
            try {
                FeatureFingerprint fp = calculator.calculate(
                    feature.position, corners, feature.id);

                FourCornerTemplate.FeatureMetadata metadata =
                    new FourCornerTemplate.FeatureMetadata(
                        feature.name,
                        feature.classId,
                        feature.className,
                        feature.required,
                        feature.tolerance
                    );

                builder.addFeature(feature.id, feature.position, fp, metadata);

                logger.debug("Added feature {}: position={}, fingerprint={}",
                    feature.id, feature.position, fp);

            } catch (Exception e) {
                logger.warn("Failed to add feature {}: {}", feature.id, e.getMessage());
            }
        }

        FourCornerTemplate template = builder.build();
        logger.info("Template built successfully: {}", template);
        return template;
    }

    /**
     * 特征点定义
     */
    public static class FeatureDefinition {
        public final String id;
        public final String name;
        public final int classId;
        public final String className;
        public final Point position;
        public final boolean required;
        public final Point tolerance;

        public FeatureDefinition(String id, String name, int classId, String className,
                               Point position, boolean required, Point tolerance) {
            this.id = id;
            this.name = name;
            this.classId = classId;
            this.className = className;
            this.position = position;
            this.required = required;
            this.tolerance = tolerance;
        }

        /**
         * 简化构造器（使用默认容差）
         */
        public FeatureDefinition(String id, String name, int classId, String className,
                               Point position, boolean required) {
            this(id, name, classId, className, position, required, new Point(10, 10));
        }

        /**
         * 最简构造器
         */
        public FeatureDefinition(String id, int classId, Point position) {
            this(id, id, classId, "", position, true, new Point(10, 10));
        }

        @Override
        public String toString() {
            return String.format("Feature[%s: %s at (%.0f,%.0f)]",
                id, name, position.x, position.y);
        }
    }

    /**
     * 从检测到的四角更新模板
     * <p>
     * 使用场景：重新校准
     *
     * @param oldTemplate    旧模板
     * @param newCorners     新检测到的四角
     * @return 更新后的模板（或 null 如果四角无效）
     */
    public FourCornerTemplate updateCorners(FourCornerTemplate oldTemplate, Point[] newCorners) {
        if (!calculator.isValidQuadrilateral(newCorners)) {
            logger.warn("Invalid new corners, update rejected");
            return null;
        }

        FourCornerTemplate.Builder builder = new FourCornerTemplate.Builder()
            .templateId(oldTemplate.getTemplateId())
            .corners(newCorners);

        // 复制所有特征点
        for (String featureId : oldTemplate.getFeatureIds()) {
            builder.addFeature(
                featureId,
                oldTemplate.getFeaturePosition(featureId),
                oldTemplate.getFingerprint(featureId),
                oldTemplate.getFeatureMetadata(featureId)
            );
        }

        logger.info("Updated corners for template: {}", oldTemplate.getTemplateId());
        return builder.build();
    }

    /**
     * 验证四角定义是否有效
     * <p>
     * 检查：
     * 1. 四个点是否构成有效四边形
     * 2. 面积是否足够大
     * 3. 角度是否合理（接近90度）
     *
     * @param corners 四角坐标
     * @return 验证结果
     */
    public ValidationResult validateCorners(Point[] corners) {
        if (corners == null || corners.length != 4) {
            return new ValidationResult(false, "必须有4个角点");
        }

        // 检查基本有效性
        if (!calculator.isValidQuadrilateral(corners)) {
            return new ValidationResult(false, "四边形无效（可能有点重合或面积太小）");
        }

        // 计算角度
        double[] angles = calculateInteriorAngles(corners);

        // 检查角度是否合理（应该在45-135度之间）
        for (int i = 0; i < 4; i++) {
            double angleDeg = Math.toDegrees(angles[i]);
            if (angleDeg < 45 || angleDeg > 135) {
                return new ValidationResult(false,
                    String.format("角%d角度异常: %.1f° (期望45°-135°)", i, angleDeg));
            }
        }

        return new ValidationResult(true,
            String.format("四边形有效，角度: %.1f°, %.1f°, %.1f°, %.1f°",
                Math.toDegrees(angles[0]),
                Math.toDegrees(angles[1]),
                Math.toDegrees(angles[2]),
                Math.toDegrees(angles[3])));
    }

    /**
     * 计算四边形内角
     */
    private double[] calculateInteriorAngles(Point[] corners) {
        double[] angles = new double[4];

        for (int i = 0; i < 4; i++) {
            Point prev = corners[(i + 3) % 4]; // 前一个点
            Point curr = corners[i];            // 当前点
            Point next = corners[(i + 1) % 4];  // 下一个点

            // 计算两个向量
            double v1x = prev.x - curr.x;
            double v1y = prev.y - curr.y;
            double v2x = next.x - curr.x;
            double v2y = next.y - curr.y;

            // 计算夹角
            double dot = v1x * v2x + v1y * v2y;
            double len1 = Math.sqrt(v1x * v1x + v1y * v1y);
            double len2 = Math.sqrt(v2x * v2x + v2y * v2y);
            double cosAngle = dot / (len1 * len2);
            cosAngle = Math.max(-1.0, Math.min(1.0, cosAngle)); // clamp
            angles[i] = Math.acos(cosAngle);
        }

        return angles;
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        public final boolean valid;
        public final String message;

        public ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
    }
}
