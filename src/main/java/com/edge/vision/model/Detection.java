package com.edge.vision.model;

import java.util.Arrays;

public class Detection {
    private String label;     // 类别名称
    private int classId;      // 类别ID
    private float[] bbox;     // [x1, y1, x2, y2] 左上角和右下角坐标
    private float centerX;    // 中心点X坐标
    private float centerY;    // 中心点Y坐标
    private float confidence; // 置信度

    public Detection(String label, int classId, float[] bbox, float confidence) {
        this.label = label;
        this.classId = classId;
        this.bbox = bbox;
        this.confidence = confidence;
        // 从bbox计算中心点
        if (bbox != null && bbox.length >= 4) {
            this.centerX = (bbox[0] + bbox[2]) / 2;
            this.centerY = (bbox[1] + bbox[3]) / 2;
        }
    }

    public Detection(String label, int classId, float[] bbox, float centerX, float centerY, float confidence) {
        this.label = label;
        this.classId = classId;
        this.bbox = bbox;
        this.centerX = centerX;
        this.centerY = centerY;
        this.confidence = confidence;
    }

    public String getLabel() { return label; }
    public int getClassId() { return classId; }
    public float[] getBbox() { return bbox; }
    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    public float getConfidence() { return confidence; }

    @Override
    public String toString() {
        return "Detection{" +
                "label='" + label + '\'' +
                ", id=" + classId +
                ", conf=" + confidence +
                ", bbox=" + Arrays.toString(bbox) +
                ", center=(" + centerX + ", " + centerY + ")" +
                '}';
    }
}