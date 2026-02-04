package com.edge.vision.dto;

import java.util.List;

/**
 * 多摄像头模板预览请求
 */
public class MultiCameraPreviewRequest {
    private String partType;
    private List<CameraCropData> cameraCrops;

    public String getPartType() {
        return partType;
    }

    public void setPartType(String partType) {
        this.partType = partType;
    }

    public List<CameraCropData> getCameraCrops() {
        return cameraCrops;
    }

    public void setCameraCrops(List<CameraCropData> cameraCrops) {
        this.cameraCrops = cameraCrops;
    }

    /**
     * 单个摄像头裁剪数据
     * cropRect 为 null 或空数组表示该摄像头没有工件画面
     */
    public static class CameraCropData {
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
