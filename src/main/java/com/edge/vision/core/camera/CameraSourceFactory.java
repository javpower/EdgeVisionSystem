package com.edge.vision.core.camera;

public class CameraSourceFactory {
    
    /**
     * 创建相机源
     * @param src 相机源标识，可以是Integer（本地摄像头索引）或String（RTSP URL）
     * @return 相机源实例
     * @throws IllegalArgumentException 如果源类型不支持
     */
    public static CameraSource create(Object src) {
        if (src == null) {
            throw new IllegalArgumentException("Camera source cannot be null");
        }
        
        if (src instanceof Integer) {
            return new LocalCameraSource((Integer) src);
        } else if (src instanceof String) {
            String strSrc = (String) src;
            // 如果是数字字符串，转换为本地摄像头索引
            try {
                int index = Integer.parseInt(strSrc);
                return new LocalCameraSource(index);
            } catch (NumberFormatException e) {
                // 否则视为RTSP URL
                return new RTSPCameraSource(strSrc);
            }
        } else {
            throw new IllegalArgumentException("Unsupported camera source type: " + src.getClass().getName());
        }
    }
}