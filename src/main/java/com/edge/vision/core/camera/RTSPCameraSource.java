package com.edge.vision.core.camera;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;

public class RTSPCameraSource implements CameraSource {
    private final String rtspUrl;
    private VideoCapture capture;

    public RTSPCameraSource(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    @Override
    public boolean open() {
        capture = new VideoCapture(rtspUrl);
        return capture.isOpened();
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