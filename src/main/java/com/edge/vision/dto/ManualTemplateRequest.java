package com.edge.vision.dto;

import java.util.List;

/**
 * 手动模板创建请求
 */
public class ManualTemplateRequest {
    private String templateId;           // 模板ID（工件类型）
    private String description;          // 描述
    private CropArea cropArea;           // 工件整体区域
    private List<ManualAnnotation> annotations; // 手动标注的细节
    private String imageData;            // Base64编码的图片
    private double toleranceX = 5.0;     // 全局X容差
    private double toleranceY = 5.0;     // 全局Y容差

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public CropArea getCropArea() { return cropArea; }
    public void setCropArea(CropArea cropArea) { this.cropArea = cropArea; }

    public List<ManualAnnotation> getAnnotations() { return annotations; }
    public void setAnnotations(List<ManualAnnotation> annotations) { this.annotations = annotations; }

    public String getImageData() { return imageData; }
    public void setImageData(String imageData) { this.imageData = imageData; }

    public double getToleranceX() { return toleranceX; }
    public void setToleranceX(double toleranceX) { this.toleranceX = toleranceX; }

    public double getToleranceY() { return toleranceY; }
    public void setToleranceY(double toleranceY) { this.toleranceY = toleranceY; }

    /**
     * 裁剪区域
     */
    public static class CropArea {
        private double x;      // 左上角X
        private double y;      // 左上角Y
        private double width;  // 宽度
        private double height; // 高度

        public CropArea() {}

        public CropArea(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }

        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }

        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }
    }

    /**
     * 手动标注
     */
    public static class ManualAnnotation {
        private String id;          // 标注ID
        private String name;        // 特征名称（如：hole, scratch等）
        private int classId;        // 类别ID
        private BoundingBox bbox;   // 边界框
        private double toleranceX;  // 单独的X容差
        private double toleranceY;  // 单独的Y容差
        private boolean required;   // 是否必需检测

        public ManualAnnotation() {
            this.required = true;
        }

        public ManualAnnotation(String id, String name, int classId, BoundingBox bbox) {
            this.id = id;
            this.name = name;
            this.classId = classId;
            this.bbox = bbox;
            this.required = true;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getClassId() { return classId; }
        public void setClassId(int classId) { this.classId = classId; }

        public BoundingBox getBbox() { return bbox; }
        public void setBbox(BoundingBox bbox) { this.bbox = bbox; }

        public double getToleranceX() { return toleranceX; }
        public void setToleranceX(double toleranceX) { this.toleranceX = toleranceX; }

        public double getToleranceY() { return toleranceY; }
        public void setToleranceY(double toleranceY) { this.toleranceY = toleranceY; }

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
    }

    /**
     * 边界框
     */
    public static class BoundingBox {
        private double x;      // 左上角X
        private double y;      // 左上角Y
        private double width;  // 宽度
        private double height; // 高度

        public BoundingBox() {}

        public BoundingBox(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }

        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }

        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }

        public double getCenterX() { return x + width / 2.0; }
        public double getCenterY() { return y + height / 2.0; }
    }
}
