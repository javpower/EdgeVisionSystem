package com.edge.vision.dto;

import java.util.List;

/**
 * 多摄像头手动模板创建请求
 */
public class ManualMultiCameraRequest {
    private String partType;
    private String description;
    private double toleranceX = 5.0;
    private double toleranceY = 5.0;
    private List<CameraTemplateData> cameraTemplates;

    public String getPartType() {
        return partType;
    }

    public void setPartType(String partType) {
        this.partType = partType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getToleranceX() {
        return toleranceX;
    }

    public void setToleranceX(double toleranceX) {
        this.toleranceX = toleranceX;
    }

    public double getToleranceY() {
        return toleranceY;
    }

    public void setToleranceY(double toleranceY) {
        this.toleranceY = toleranceY;
    }

    public List<CameraTemplateData> getCameraTemplates() {
        return cameraTemplates;
    }

    public void setCameraTemplates(List<CameraTemplateData> cameraTemplates) {
        this.cameraTemplates = cameraTemplates;
    }

    /**
     * 单个摄像头模板数据
     */
    public static class CameraTemplateData {
        private int cameraId;
        private int[] cropRect;  // [x, y, width, height]
        private List<ManualAnnotation> annotations;

        public int getCameraId() {
            return cameraId;
        }

        public void setCameraId(int cameraId) {
            this.cameraId = cameraId;
        }

        public int[] getCropRect() {
            return cropRect;
        }

        public void setCropRect(int[] cropRect) {
            this.cropRect = cropRect;
        }

        public List<ManualAnnotation> getAnnotations() {
            return annotations;
        }

        public void setAnnotations(List<ManualAnnotation> annotations) {
            this.annotations = annotations;
        }
    }

    /**
     * 手动标注
     */
    public static class ManualAnnotation {
        private String id;
        private String name;
        private int classId;
        private int[] bbox;  // [x, y, width, height]
        private double toleranceX;
        private double toleranceY;
        private boolean required;

        public ManualAnnotation() {
            this.required = true;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getClassId() {
            return classId;
        }

        public void setClassId(int classId) {
            this.classId = classId;
        }

        public int[] getBbox() {
            return bbox;
        }

        public void setBbox(int[] bbox) {
            this.bbox = bbox;
        }

        public double getToleranceX() {
            return toleranceX;
        }

        public void setToleranceX(double toleranceX) {
            this.toleranceX = toleranceX;
        }

        public double getToleranceY() {
            return toleranceY;
        }

        public void setToleranceY(double toleranceY) {
            this.toleranceY = toleranceY;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }
}
