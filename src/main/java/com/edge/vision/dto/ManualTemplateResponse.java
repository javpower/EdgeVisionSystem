package com.edge.vision.dto;

import java.util.List;

/**
 * 手动模板创建响应
 */
public class ManualTemplateResponse {
    private boolean success;
    private String message;
    private String templateId;
    private String previewUrl;           // 预览图片URL
    private List<AnnotationPreview> annotations; // 标注预览

    public ManualTemplateResponse() {}

    public static ManualTemplateResponse success(String message, String templateId, String previewUrl, List<AnnotationPreview> annotations) {
        ManualTemplateResponse response = new ManualTemplateResponse();
        response.success = true;
        response.message = message;
        response.templateId = templateId;
        response.previewUrl = previewUrl;
        response.annotations = annotations;
        return response;
    }

    public static ManualTemplateResponse error(String message) {
        ManualTemplateResponse response = new ManualTemplateResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getPreviewUrl() { return previewUrl; }
    public void setPreviewUrl(String previewUrl) { this.previewUrl = previewUrl; }

    public List<AnnotationPreview> getAnnotations() { return annotations; }
    public void setAnnotations(List<AnnotationPreview> annotations) { this.annotations = annotations; }

    /**
     * 标注预览
     */
    public static class AnnotationPreview {
        private String id;
        private String name;
        private int classId;
        private double x;
        private double y;
        private double width;
        private double height;

        public AnnotationPreview() {}

        public AnnotationPreview(String id, String name, int classId, double x, double y, double width, double height) {
            this.id = id;
            this.name = name;
            this.classId = classId;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getClassId() { return classId; }
        public void setClassId(int classId) { this.classId = classId; }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }

        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }

        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }
    }
}
