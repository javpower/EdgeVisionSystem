package com.edge.vision.core.camera;

public class CameraSourceFactory {

    private static final String MVS_IP_PREFIX = "mvs:ip:";

    /**
     * 创建相机源
     * @param src 相机源标识
     *             - Integer: 本地摄像头索引
     *             - "rtsp://...": RTSP 网络摄像头
     *             - "mvs:ip:x.x.x.x": MVS GigE 工业相机（按 IP 指定）
     *             - 数字字符串 ("0", "1"): 本地摄像头索引
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

            // 检查是否是 MVS GigE 相机
            if (strSrc.startsWith(MVS_IP_PREFIX)) {
                String ipAddress = strSrc.substring(MVS_IP_PREFIX.length());
                if (isValidIpAddress(ipAddress)) {
                    return new MVSCameraSource(ipAddress);
                } else {
                    throw new IllegalArgumentException("Invalid IP address for MVS camera: " + ipAddress);
                }
            }

            // 检查是否是 RTSP URL
            if (strSrc.toLowerCase().startsWith("rtsp://")) {
                return new RTSPCameraSource(strSrc);
            }

            // 尝试作为数字字符串解析（本地摄像头索引）
            try {
                int index = Integer.parseInt(strSrc);
                return new LocalCameraSource(index);
            } catch (NumberFormatException e) {
                return new RTSPCameraSource(strSrc);
            }
        } else {
            throw new IllegalArgumentException("Unsupported camera source type: " + src.getClass().getName());
        }
    }

    /**
     * 验证 IP 地址格式
     */
    private static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }

        return true;
    }
}