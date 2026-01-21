package com.edge.vision.core.topology.fourcorner;

import com.edge.vision.core.template.model.Point;

import java.util.*;

/**
 * 四角工件模板
 * <p>
 * 核心概念：
 * - 工件的四个角定义了"世界坐标系"
 * - 每个特征点通过到四角的位置关系进行指纹化
 * <p>
 * 角点顺序（顺时针）：
 * - Corner 0: 左上 (Top-Left)
 * - Corner 1: 右上 (Top-Right)
 * - Corner 2: 右下 (Bottom-Right)
 * - Corner 3: 左下 (Bottom-Left)
 */
public class FourCornerTemplate {
    // 模板ID
    private final String templateId;

    // 四个角坐标 [TL, TR, BR, BL]
    private final Point[] corners;

    // 特征点指纹库：featureId -> 指纹
    private final Map<String, FeatureFingerprint> fingerprintMap;

    // 特征点原始数据：featureId -> 原始位置
    private final Map<String, Point> featurePositions;

    // 特征点元数据：featureId -> 元数据
    private final Map<String, FeatureMetadata> featureMetadata;

    /**
     * 特征元数据
     */
    public static class FeatureMetadata {
        public final String name;
        public final int classId;
        public final String className;
        public final boolean required;
        public final Point tolerance; // (x, y) 容差

        public FeatureMetadata(String name, int classId, String className,
                              boolean required, Point tolerance) {
            this.name = name;
            this.classId = classId;
            this.className = className;
            this.required = required;
            this.tolerance = tolerance;
        }
    }

    private FourCornerTemplate(Builder builder) {
        this.templateId = builder.templateId;
        this.corners = builder.corners;
        this.fingerprintMap = new HashMap<>(builder.fingerprints);
        this.featurePositions = new HashMap<>(builder.featurePositions);
        this.featureMetadata = new HashMap<>(builder.featureMetadata);
    }

    public String getTemplateId() {
        return templateId;
    }

    public Point[] getCorners() {
        return corners;
    }

    public Point getCorner(int index) {
        if (index < 0 || index >= 4) {
            throw new IllegalArgumentException("Corner index must be 0-3");
        }
        return corners[index];
    }

    public FeatureFingerprint getFingerprint(String featureId) {
        return fingerprintMap.get(featureId);
    }

    public Collection<FeatureFingerprint> getAllFingerprints() {
        return fingerprintMap.values();
    }

    public Point getFeaturePosition(String featureId) {
        return featurePositions.get(featureId);
    }

    public FeatureMetadata getFeatureMetadata(String featureId) {
        return featureMetadata.get(featureId);
    }

    public Set<String> getFeatureIds() {
        return fingerprintMap.keySet();
    }

    public int getFeatureCount() {
        return fingerprintMap.size();
    }

    /**
     * 获取所有必需的特征
     */
    public Set<String> getRequiredFeatureIds() {
        Set<String> required = new HashSet<>();
        for (Map.Entry<String, FeatureMetadata> entry : featureMetadata.entrySet()) {
            if (entry.getValue().required) {
                required.add(entry.getKey());
            }
        }
        return required;
    }

    public static class Builder {
        private String templateId;
        private Point[] corners;
        private Map<String, FeatureFingerprint> fingerprints = new HashMap<>();
        private Map<String, Point> featurePositions = new HashMap<>();
        private Map<String, FeatureMetadata> featureMetadata = new HashMap<>();

        public Builder templateId(String id) {
            this.templateId = id;
            return this;
        }

        public Builder corners(Point[] corners) {
            if (corners == null || corners.length != 4) {
                throw new IllegalArgumentException("Must have exactly 4 corners");
            }
            this.corners = corners;
            return this;
        }

        public Builder corners(Point tl, Point tr, Point br, Point bl) {
            this.corners = new Point[]{tl, tr, br, bl};
            return this;
        }

        /**
         * 添加一个特征点及其指纹
         */
        public Builder addFeature(String featureId, Point position,
                                 FeatureFingerprint fingerprint,
                                 FeatureMetadata metadata) {
            this.fingerprints.put(featureId, fingerprint);
            this.featurePositions.put(featureId, position);
            this.featureMetadata.put(featureId, metadata);
            return this;
        }

        public FourCornerTemplate build() {
            if (templateId == null || templateId.isEmpty()) {
                throw new IllegalArgumentException("Template ID is required");
            }
            if (corners == null || corners.length != 4) {
                throw new IllegalArgumentException("Must have exactly 4 corners");
            }
            return new FourCornerTemplate(this);
        }
    }

    @Override
    public String toString() {
        return String.format("FourCornerTemplate[id=%s, corners=[TL(%s),TR(%s),BR(%s),BL(%s)], features=%d]",
            templateId, corners[0], corners[1], corners[2], corners[3], fingerprintMap.size());
    }
}
