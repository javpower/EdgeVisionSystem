package com.edge.vision.dto;

import java.util.List;

/**
 * 多摄像头截图响应
 */
public class MultiCameraCaptureResponse {
    private boolean success;
    private String message;
    private String partType;
    private List<CameraImage> cameras;

    public MultiCameraCaptureResponse() {
    }

    public MultiCameraCaptureResponse(boolean success, String message, String partType, List<CameraImage> cameras) {
        this.success = success;
        this.message = message;
        this.partType = partType;
        this.cameras = cameras;
    }

    public static MultiCameraCaptureResponse success(String partType, List<CameraImage> cameras) {
        return new MultiCameraCaptureResponse(true, "截图成功", partType, cameras);
    }

    public static MultiCameraCaptureResponse error(String message) {
        return new MultiCameraCaptureResponse(false, message, null, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPartType() {
        return partType;
    }

    public void setPartType(String partType) {
        this.partType = partType;
    }

    public List<CameraImage> getCameras() {
        return cameras;
    }

    public void setCameras(List<CameraImage> cameras) {
        this.cameras = cameras;
    }

    /**
     * 单个摄像头图像数据
     */
    public static class CameraImage {
        private int cameraId;
        private String imageUrl;

        public CameraImage() {
        }

        public CameraImage(int cameraId, String imageUrl) {
            this.cameraId = cameraId;
            this.imageUrl = imageUrl;
        }

        public int getCameraId() {
            return cameraId;
        }

        public void setCameraId(int cameraId) {
            this.cameraId = cameraId;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }
    }
}
