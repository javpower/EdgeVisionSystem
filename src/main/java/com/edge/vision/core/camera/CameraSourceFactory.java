package com.edge.vision.core.camera;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CameraSourceFactory {
    private static final Logger logger = LoggerFactory.getLogger(CameraSourceFactory.class);

    private static final String MVS_IP_PREFIX = "mvs:";
    private static final String MVS_CAMERA_CLASS_NAME = "com.edge.vision.core.camera.MVSCameraSource";
    private static Boolean mvsAvailable = null;
    private static final Object mvsCheckLock = new Object();

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
                String index = strSrc.substring(MVS_IP_PREFIX.length());
                return createMVSCameraSource(index);
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
     * 使用反射创建 MVS 相机源（可选支持）
     * 如果 MVS SDK 不可用，返回 null
     */
    private static CameraSource createMVSCameraSource(String index) {
        // 检查 MVS SDK 是否可用（缓存结果）
        if (mvsAvailable == null) {
            synchronized (mvsCheckLock) {
                if (mvsAvailable == null) {
                    mvsAvailable = checkMVSAvailable();
                }
            }
        }

        if (!mvsAvailable) {
            logger.error("MVS camera requested but MVS SDK is not available. Please ensure MvCameraControlWrapper.jar is in the classpath.");
            throw new IllegalArgumentException("MVS SDK not available. Cannot create MVS camera source: " + index);
        }

        try {
            // 使用反射创建 MVSCameraSource 实例
            Class<?> mvsClass = Class.forName(MVS_CAMERA_CLASS_NAME);
            return (CameraSource) mvsClass.getConstructor(String.class).newInstance(index);
        } catch (Exception e) {
            logger.error("Failed to create MVS camera source", e);
            throw new IllegalArgumentException("Failed to create MVS camera source: " + e.getMessage(), e);
        }
    }

    /**
     * 检查 MVS SDK 是否可用
     */
    private static boolean checkMVSAvailable() {
        try {
            Class.forName(MVS_CAMERA_CLASS_NAME);
            logger.info("MVS SDK is available");
            return true;
        } catch (ClassNotFoundException e) {
            logger.warn("MVS SDK is not available: MvCameraControlWrapper.jar not found in classpath. MVS camera support will be disabled.");
            return false;
        }
    }

    /**
     * 检查 MVS SDK 是否可用（公共方法，用于外部查询）
     */
    public static boolean isMVSAvailable() {
        if (mvsAvailable == null) {
            synchronized (mvsCheckLock) {
                if (mvsAvailable == null) {
                    mvsAvailable = checkMVSAvailable();
                }
            }
        }
        return mvsAvailable;
    }

}