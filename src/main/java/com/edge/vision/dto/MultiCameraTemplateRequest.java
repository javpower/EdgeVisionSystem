package com.edge.vision.dto;

import java.util.List;

/**
 * 多摄像头模板保存请求
 */
public class MultiCameraTemplateRequest {
    private String partType;
    private double toleranceX = 20.0;
    private double toleranceY = 20.0;
    private List<CameraTemplateData> cameraTemplates;

    public String getPartType() {
        return partType;
    }

    public void setPartType(String partType) {
        this.partType = partType;
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
     * cropRect 为 null 或空数组表示该摄像头没有工件画面，创建空模板
     */
    public static class CameraTemplateData {
        private int cameraId;
        private int[] cropRect;  // [x, y, width, height]，null表示无工件画面

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

        /**
         * 判断是否有工件画面
         */
        public boolean hasImage() {
            return cropRect != null && cropRect.length >= 4;
        }
    }
}
