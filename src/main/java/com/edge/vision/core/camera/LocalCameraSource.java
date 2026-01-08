package com.edge.vision.core.camera;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalCameraSource implements CameraSource {
    private static final Logger logger = LoggerFactory.getLogger(LocalCameraSource.class);
    private final int index;
    private VideoCapture capture;

    public LocalCameraSource(int index) {
        this.index = index;
    }

    @Override
    public boolean open() {
        // macOS 上使用 AVFoundation 后端
        String osName = System.getProperty("os.name").toLowerCase();
        int backendIndex;

        if (osName.contains("mac") || osName.contains("darwin")) {
            // macOS: 使用 AVFoundation
            backendIndex = org.opencv.videoio.Videoio.CAP_AVFOUNDATION;
            logger.info("Detected macOS, using AVFOUNDATION backend for camera {}", index);
        } else if (osName.contains("win")) {
            // Windows: 使用 DirectShow 或 MSMF
            backendIndex = org.opencv.videoio.Videoio.CAP_DSHOW;
            logger.info("Detected Windows, using DSHOW backend for camera {}", index);
        } else {
            // Linux: 使用 V4L2
            backendIndex = org.opencv.videoio.Videoio.CAP_V4L2;
            logger.info("Detected Linux, using V4L2 backend for camera {}", index);
        }

        // 尝试使用指定后端打开摄像头
        capture = new VideoCapture(backendIndex + index);

        // 如果指定后端失败，尝试默认方式
        if (!capture.isOpened()) {
            logger.warn("Failed to open camera {} with backend {}, trying default...", index, backendIndex);
            capture = new VideoCapture(index);
        }

        if (!capture.isOpened()) {
            logger.error("Failed to open camera {}", index);
            return false;
        }

        logger.info("Successfully opened camera {}", index);

        // 设置摄像头参数以获得更好的性能
        capture.set(org.opencv.videoio.Videoio.CAP_PROP_FRAME_WIDTH, 1280);
        capture.set(org.opencv.videoio.Videoio.CAP_PROP_FRAME_HEIGHT, 720);
        capture.set(org.opencv.videoio.Videoio.CAP_PROP_FPS, 30);

        // 读取一帧测试
        Mat testFrame = new Mat();
        boolean success = capture.read(testFrame);
        if (!success || testFrame.empty()) {
            logger.error("Camera {} opened but cannot read frames", index);
            testFrame.release();
            capture.release();
            return false;
        }
        testFrame.release();
        logger.info("Camera {} test read successful, size: {}x{}",
            index,
            (int)capture.get(org.opencv.videoio.Videoio.CAP_PROP_FRAME_WIDTH),
            (int)capture.get(org.opencv.videoio.Videoio.CAP_PROP_FRAME_HEIGHT));

        return true;
    }

    @Override
    public Mat read() {
        if (capture == null || !capture.isOpened()) {
            return null;
        }
        
        Mat frame = new Mat();
        boolean success = capture.read(frame);
        
        if (!success || frame.empty()) {
            frame.release();
            return null;
        }
        
        return frame;
    }

    @Override
    public void close() {
        if (capture != null) {
            capture.release();
            capture = null;
        }
    }

    @Override
    public boolean isOpened() {
        return capture != null && capture.isOpened();
    }
}