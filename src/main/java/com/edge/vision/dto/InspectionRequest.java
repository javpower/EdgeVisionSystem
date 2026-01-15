package com.edge.vision.dto;

import java.util.List;

/**
 * 质量检测请求
 */
public class InspectionRequest {
    private String partType;
    private List<DetectedObject> detections;

    public String getPartType() { return partType; }
    public void setPartType(String partType) { this.partType = partType; }

    public List<DetectedObject> getDetections() { return detections; }
    public void setDetections(List<DetectedObject> detections) { this.detections = detections; }

    /**
     * 检测对象
     */
    public static class DetectedObject {
        private int classId;
        private String className;
        private String label;  // 前端发送的标签名称
        private double centerX;
        private double centerY;
        private double width;
        private double height;
        private double confidence;

        public int getClassId() { return classId; }
        public void setClassId(int classId) { this.classId = classId; }

        public String getClassName() {
            return className != null ? className : label;
        }
        public void setClassName(String className) { this.className = className; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public double getCenterX() { return centerX; }
        public void setCenterX(double centerX) { this.centerX = centerX; }

        public double getCenterY() { return centerY; }
        public void setCenterY(double centerY) { this.centerY = centerY; }

        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }

        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }
}
