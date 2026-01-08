package com.edge.vision.service;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.camera.CameraSource;
import com.edge.vision.core.camera.CameraSourceFactory;
import com.edge.vision.core.stitcher.SimpleStitchStrategy;
import com.edge.vision.core.stitcher.StitchStrategy;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CameraService {
    private static final Logger logger = LoggerFactory.getLogger(CameraService.class);

    @Autowired
    private YamlConfig config;

    // StitchStrategy 将在需要时创建

    private final List<CameraSource> cameraSources = new ArrayList<>();
    private final List<Mat> currentFrames = new ArrayList<>();
    private final List<BlockingQueue<byte[]>> streamQueues = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService executor;

    @PostConstruct
    public void init() {
        // 初始化 OpenCV - 兼容 native-image 和普通 JAR 运行
        try {
            // 检测是否在 native-image 环境中运行
            boolean isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

            if (isNativeImage) {
                // Native-image 环境: 尝试直接加载系统 OpenCV 库
                logger.info("Running in native-image mode, attempting to load OpenCV from system paths");

                // 尝试加载 OpenCV 库
                System.loadLibrary("opencv_java470");
                logger.info("OpenCV loaded successfully from system library (native-image mode)");
            } else {
                // 普通 JAR 环境: 使用 openpnp 的方式加载
                nu.pattern.OpenCV.loadShared();
                logger.info("OpenCV loaded successfully, version: {}", org.opencv.core.Core.VERSION);
            }
        } catch (UnsatisfiedLinkError e) {
            logger.error("Failed to load OpenCV native library. In native-image mode, OpenCV must be installed on the system.");
            throw new RuntimeException("OpenCV native library not found. In native-image mode, please install OpenCV or place libopencv_java470.{dylib|so|dll} next to the executable.", e);
        } catch (Exception e) {
            logger.error("Failed to load OpenCV", e);
            throw new RuntimeException("Failed to load OpenCV", e);
        }
    }

    /**
     * 启动所有摄像头
     */
    public void startCameras() {
        if (running.get()) {
            return;
        }

        cameraSources.clear();
        currentFrames.clear();
        streamQueues.clear();

        List<Object> sources = config.getCameras().getSources();
        if (sources == null || sources.isEmpty()) {
            logger.warn("No camera sources configured in application.yml");
            return;
        }

        // 创建线程池
        executor = Executors.newScheduledThreadPool(sources.size() + 1);

        // 先尝试打开所有摄像头
        for (int i = 0; i < sources.size(); i++) {
            Object source = sources.get(i);
            try {
                logger.info("Attempting to open camera {} with source: {}", i, source);
                CameraSource cameraSource = CameraSourceFactory.create(source);

                if (cameraSource.open()) {
                    cameraSources.add(cameraSource);
                    // 只为成功打开的摄像头初始化队列和帧存储
                    currentFrames.add(null);
                    streamQueues.add(new LinkedBlockingQueue<>(5));
                    logger.info("Camera {} started successfully: {}", cameraSources.size() - 1, source);
                } else {
                    logger.warn("Camera {} failed to open: {}. Is it connected?", i, source);
                }
            } catch (Exception e) {
                logger.error("Error initializing camera {} with source {}: {}", i, source, e.getMessage(), e);
            }
        }

        if (cameraSources.isEmpty()) {
            logger.warn("No cameras could be opened. Application will continue in degraded mode.");
            logger.warn("For testing, you can: 1) Connect a camera, 2) Use RTSP URLs, or 3) Use video files as sources");
            return;
        }

        running.set(true);

        // 为每个成功打开的摄像头启动读取任务
        for (int i = 0; i < cameraSources.size(); i++) {
            final int cameraIndex = i;
            executor.scheduleWithFixedDelay(() -> readCamera(cameraIndex), 0, 33, TimeUnit.MILLISECONDS);
            logger.info("Started read task for camera {}", cameraIndex);
        }

        logger.info("{} camera(s) started successfully", cameraSources.size());
    }

    /**
     * 读取单个摄像头帧（简化版，避免崩溃）
     */
    private void readCamera(int cameraIndex) {
        if (!running.get() || cameraIndex >= cameraSources.size()) {
            return;
        }

        try {
            CameraSource cameraSource = cameraSources.get(cameraIndex);
            if (!cameraSource.isOpened()) {
                return;
            }

            Mat frame = cameraSource.read();
            if (frame != null && !frame.empty()) {
                // 释放旧帧
                Mat oldFrame = currentFrames.get(cameraIndex);
                if (oldFrame != null) {
                    oldFrame.release();
                }

                // 克隆帧用于存储和编码（避免原始帧被复用的问题）
                Mat clonedFrame = frame.clone();
                currentFrames.set(cameraIndex, clonedFrame);

                // 转换为 JPEG 并添加到队列（使用克隆的帧）
                try {
                    MatOfByte mob = new MatOfByte();
                    MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85);
                    Imgcodecs.imencode(".jpg", clonedFrame, mob, params);
                    byte[] jpegBytes = mob.toArray();
                    mob.release();

                    if (jpegBytes != null && jpegBytes.length > 0) {
                        boolean added = streamQueues.get(cameraIndex).offer(jpegBytes);
                        if (!added) {
                            // 队列满了，说明消费速度跟不上
                            streamQueues.get(cameraIndex).poll(); // 移除最老的帧
                            streamQueues.get(cameraIndex).offer(jpegBytes); // 添加新帧
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to encode frame for camera {}: {}", cameraIndex, e.getMessage());
                }

                frame.release();
            } else {
                logger.debug("Camera {} returned empty frame", cameraIndex);
            }
        } catch (Exception e) {
            logger.error("Error reading from camera {}: {}", cameraIndex, e.getMessage());
        }
    }

    /**
     * 停止所有摄像头
     */
    @PreDestroy
    public void stopCameras() {
        if (!running.get()) {
            return;
        }

        running.set(false);

        // 停止线程池
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 关闭所有相机源并释放帧
        for (CameraSource source : cameraSources) {
            try {
                source.close();
            } catch (Exception e) {
                logger.warn("Error closing camera source", e);
            }
        }

        for (Mat frame : currentFrames) {
            if (frame != null) {
                frame.release();
            }
        }

        cameraSources.clear();
        currentFrames.clear();
        streamQueues.clear();

        logger.info("All cameras stopped");
    }

    /**
     * 获取单个摄像头的当前帧（Base64）
     */
    public String getCameraImageBase64(int cameraIndex) {
        if (cameraIndex < 0 || cameraIndex >= currentFrames.size()) {
            return null;
        }

        Mat frame = currentFrames.get(cameraIndex);
        if (frame == null || frame.empty()) {
            return null;
        }

        try {
            MatOfByte mob = new MatOfByte();
            Imgcodecs.imencode(".jpg", frame, mob);
            byte[] bytes = mob.toArray();
            mob.release();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            logger.error("Failed to convert camera {} frame to base64", cameraIndex, e);
            return null;
        }
    }

    /**
     * 获取单个摄像头的 MJPEG 帧数据
     */
    public byte[] getMjpegFrame(int cameraIndex) {
        if (cameraIndex < 0 || cameraIndex >= streamQueues.size()) {
            return null;
        }

        try {
            // 使用较短的超时时间（50ms），避免阻塞视频流
            return streamQueues.get(cameraIndex).poll(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 获取所有摄像头的当前帧（用于预览）
     */
    public List<String> getAllCameraFramesBase64() {
        List<String> frames = new ArrayList<>();
        for (int i = 0; i < Math.min(currentFrames.size(), cameraSources.size()); i++) {
            String frame = getCameraImageBase64(i);
            frames.add(frame);
        }
        return frames;
    }

    /**
     * 获取拼接后的图像（用于检测）
     */
    public String getStitchedImageBase64() {
        List<Mat> frames = new ArrayList<>();
        for (int i = 0; i < cameraSources.size(); i++) {
            Mat frame = currentFrames.get(i);
            if (frame != null && !frame.empty()) {
                frames.add(frame.clone());
            }
        }
        if (frames.size()==0) {
            return null;
        }
        try {
            Mat stitched;
            // 简单拼接，不带融合（避免复杂处理）
            if(frames.size()>=2){
                StitchStrategy stitchStrategy = new SimpleStitchStrategy(true);
                stitched = stitchStrategy.stitch(frames);
            }else {
                stitched=frames.get(0);
            }
            MatOfByte mob = new MatOfByte();
            Imgcodecs.imencode(".jpg", stitched, mob);
            byte[] bytes = mob.toArray();
            mob.release();
            stitched.release();

            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            logger.error("Failed to stitch images", e);
            return null;
        } finally {
            for (Mat frame : frames) {
                frame.release();
            }
        }
    }

    /**
     * 获取拼接后的 MJPEG 帧数据
     */
    public byte[] getStitchedMjpegFrame() {
        List<Mat> frames = new ArrayList<>();
        for (int i = 0; i < cameraSources.size(); i++) {
            Mat frame = currentFrames.get(i);
            if (frame != null && !frame.empty()) {
                frames.add(frame.clone());
            }
        }

        if (frames.size()==0) {
            return null;
        }
        try {
            Mat stitched;
            // 简单拼接，不带融合（避免复杂处理）
            if(frames.size()>=2){
                StitchStrategy stitchStrategy = new SimpleStitchStrategy(true);
                stitched = stitchStrategy.stitch(frames);
            }else {
                stitched=frames.get(0);
            }
            MatOfByte mob = new MatOfByte();
            Imgcodecs.imencode(".jpg", stitched, mob);
            byte[] bytes = mob.toArray();
            mob.release();
            stitched.release();

            return bytes;
        } catch (Exception e) {
            logger.error("Failed to stitch images for MJPEG", e);
            return null;
        } finally {
            for (Mat frame : frames) {
                frame.release();
            }
        }
    }

    /**
     * 获取摄像头数量
     */
    public int getCameraCount() {
        return cameraSources.size();
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 获取配置的摄像头数量
     */
    public int getConfiguredCameraCount() {
        return config.getCameras().getSources() != null ? config.getCameras().getSources().size() : 0;
    }
}
