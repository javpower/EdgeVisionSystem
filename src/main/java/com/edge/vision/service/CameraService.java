package com.edge.vision.service;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.camera.CameraSource;
import com.edge.vision.core.camera.CameraSourceFactory;
import com.edge.vision.core.stitcher.StitchStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CameraService {
    private static final Logger logger = LoggerFactory.getLogger(CameraService.class);

    // 配置参数
    private static final int TARGET_FPS = 30;
    private static final int FRAME_INTERVAL_MS = 1000 / TARGET_FPS; // 33ms
    private static final int JPEG_QUALITY = 70; // 降低质量以减少带宽，平衡画质和性能
    private static final int STREAM_QUEUE_SIZE = 10; // 增大队列缓冲
    private static final int MAX_FRAME_WAIT_MS = 100; // 最大等待时间

    // 流优化参数
    private static final int STREAM_JPEG_QUALITY = 50; // 流媒体使用更低的JPEG质量
    private static final double STREAM_SCALE_FACTOR = 0.25; // 流媒体缩放到1/4 (4K->1080p)

    @Autowired
    private YamlConfig config;

    @Autowired
    private StitchConfigService stitchConfigService;

    private final List<CameraSource> cameraSources = new CopyOnWriteArrayList<>();
    private final List<Mat> currentFrames = new CopyOnWriteArrayList<>();
    private final List<BlockingQueue<FrameData>> streamQueues = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong globalFrameSequence = new AtomicLong(0); // 全局帧序列号

    private ScheduledExecutorService executor;
    private ExecutorService cameraExecutor;

    /**
     * 帧数据封装类，包含时间戳和序列号
     */
    public static class FrameData {
        private final byte[] data;
        private final long timestamp;
        private final long sequence;

        public FrameData(byte[] data, long timestamp, long sequence) {
            this.data = data;
            this.timestamp = timestamp;
            this.sequence = sequence;
        }

        public byte[] getData() { return data; }
        public long getTimestamp() { return timestamp; }
        public long getSequence() { return sequence; }
    }

    @PostConstruct
    public void init() {
        try {
            logger.info("OpenCV version: {}", org.opencv.core.Core.VERSION);
            logger.info("CameraService initialized - Target FPS: {}, Frame Interval: {}ms, JPEG Quality: {}", 
                    TARGET_FPS, FRAME_INTERVAL_MS, JPEG_QUALITY);
        } catch (Exception e) {
            logger.error("Failed to initialize CameraService", e);
            throw new RuntimeException("Failed to initialize CameraService", e);
        }
    }

    /**
     * 启动所有摄像头（同步启动）
     */
    public void startCameras() {
        if (running.get()) {
            logger.warn("Cameras already running");
            return;
        }

        cameraSources.clear();
        currentFrames.clear();
        streamQueues.clear();
        globalFrameSequence.set(0);

        List<Object> sources = config.getCameras().getSources();
        if (sources == null || sources.isEmpty()) {
            logger.warn("No camera sources configured in application.yml");
            return;
        }

        // 使用固定大小线程池
        executor = Executors.newScheduledThreadPool(sources.size() + 2, r -> {
            Thread t = new Thread(r, "Camera-Scheduler");
            t.setDaemon(true);
            return t;
        });
        
        cameraExecutor = Executors.newFixedThreadPool(sources.size(), r -> {
            Thread t = new Thread(r, "Camera-Capture");
            t.setDaemon(true);
            return t;
        });

        // 第一阶段：尝试打开所有摄像头
        List<CameraSource> openedSources = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Object source = sources.get(i);
            try {
                logger.info("Opening camera {} with source: {}", i, source);
                CameraSource cameraSource = CameraSourceFactory.create(source);

                if (cameraSource.open()) {
                    openedSources.add(cameraSource);
                    currentFrames.add(null);
                    streamQueues.add(new LinkedBlockingQueue<>(STREAM_QUEUE_SIZE));
                    logger.info("Camera {} opened successfully", openedSources.size() - 1);
                } else {
                    logger.warn("Camera {} failed to open: {}", i, source);
                }
            } catch (Exception e) {
                logger.error("Error opening camera {} with source {}: {}", i, source, e.getMessage(), e);
            }
        }

        if (openedSources.isEmpty()) {
            logger.warn("No cameras could be opened");
            shutdownExecutors();
            return;
        }

        cameraSources.addAll(openedSources);
        running.set(true);

        // 第二阶段：同步预热 - 丢弃前几帧确保所有摄像头都准备好
        logger.info("Preheating cameras...");
        CountDownLatch preheatLatch = new CountDownLatch(cameraSources.size());
        List<Mat> preheatFrames = new CopyOnWriteArrayList<>();
        
        for (int i = 0; i < cameraSources.size(); i++) {
            final int cameraIndex = i;
            cameraExecutor.submit(() -> {
                try {
                    CameraSource source = cameraSources.get(cameraIndex);
                    // 丢弃前5帧，让摄像头稳定
                    for (int j = 0; j < 5; j++) {
                        Mat frame = source.read();
                        if (frame != null && !frame.empty()) {
                            frame.release();
                        }
                        Thread.sleep(10);
                    }
                    // 读取稳定帧
                    Mat stableFrame = source.read();
                    if (stableFrame != null && !stableFrame.empty()) {
                        preheatFrames.add(stableFrame.clone());
                        stableFrame.release();
                    }
                } catch (Exception e) {
                    logger.error("Error preheating camera {}", cameraIndex, e);
                } finally {
                    preheatLatch.countDown();
                }
            });
        }

        try {
            preheatLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Preheat interrupted");
        }

        // 释放预热帧
        for (Mat frame : preheatFrames) {
            if (frame != null) frame.release();
        }

        // 第三阶段：同步启动所有摄像头的采集任务
        logger.info("Starting synchronized capture for {} cameras", cameraSources.size());
        
        // 使用 CountDownLatch 确保所有摄像头同时开始采集
        CountDownLatch startLatch = new CountDownLatch(1);
        
        for (int i = 0; i < cameraSources.size(); i++) {
            final int cameraIndex = i;
            executor.scheduleAtFixedRate(() -> {
                try {
                    // 等待同步信号
                    startLatch.await();
                    readCamera(cameraIndex);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, 0, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        // 同时释放所有摄像头采集任务
        startLatch.countDown();
        
        logger.info("{} camera(s) started successfully at {} FPS", cameraSources.size(), TARGET_FPS);
    }

    /**
     * 读取单个摄像头帧（优化版）
     */
    private void readCamera(int cameraIndex) {
        if (!running.get() || cameraIndex >= cameraSources.size()) {
            return;
        }

        CameraSource cameraSource = null;
        Mat frame = null;
        Mat clonedFrame = null;
        MatOfByte mob = null;

        try {
            cameraSource = cameraSources.get(cameraIndex);
            if (!cameraSource.isOpened()) {
                return;
            }

            frame = cameraSource.read();
            if (frame == null || frame.empty()) {
                return;
            }

            // 克隆帧用于存储
            clonedFrame = frame.clone();
            
            // 原子更新当前帧
            Mat oldFrame = currentFrames.set(cameraIndex, clonedFrame);
            if (oldFrame != null) {
                oldFrame.release();
            }

            // 编码为 JPEG
            mob = new MatOfByte();
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
            
            if (Imgcodecs.imencode(".jpg", clonedFrame, mob, params)) {
                byte[] jpegBytes = mob.toArray();
                if (jpegBytes != null && jpegBytes.length > 0) {
                    long sequence = globalFrameSequence.incrementAndGet();
                    FrameData frameData = new FrameData(jpegBytes, System.currentTimeMillis(), sequence);
                    
                    BlockingQueue<FrameData> queue = streamQueues.get(cameraIndex);
                    if (!queue.offer(frameData)) {
                        // 队列满了，移除最老的帧，添加新帧
                        queue.poll();
                        queue.offer(frameData);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error reading camera {}: {}", cameraIndex, e.getMessage());
        } finally {
            // 释放资源
            if (frame != null) {
                frame.release();
            }
            if (mob != null) {
                mob.release();
            }
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

        logger.info("Stopping cameras...");
        running.set(false);

        shutdownExecutors();

        // 关闭所有相机源
        for (CameraSource source : cameraSources) {
            try {
                source.close();
            } catch (Exception e) {
                logger.warn("Error closing camera source: {}", e.getMessage());
            }
        }

        // 释放所有帧
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

    private void shutdownExecutors() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            try {
                if (!cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    cameraExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cameraExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            cameraExecutor = null;
        }
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

        MatOfByte mob = null;
        try {
            mob = new MatOfByte();
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
            if (Imgcodecs.imencode(".jpg", frame, mob, params)) {
                byte[] bytes = mob.toArray();
                return Base64.getEncoder().encodeToString(bytes);
            }
            return null;
        } catch (Exception e) {
            logger.error("Failed to encode camera {} frame to base64", cameraIndex, e);
            return null;
        } finally {
            if (mob != null) {
                mob.release();
            }
        }
    }

    /**
     * 获取单个摄像头的当前帧（Mat，直接返回，避免base64转换）
     * 注意：调用方需要 clone() 或在不使用时注意原Mat会被更新
     *
     * @param cameraIndex 摄像头索引
     * @return 当前帧的克隆（需要由调用方释放）
     */
    public Mat getCameraImageMat(int cameraIndex) {
        if (cameraIndex < 0 || cameraIndex >= currentFrames.size()) {
            return null;
        }

        Mat frame = currentFrames.get(cameraIndex);
        if (frame == null || frame.empty()) {
            return null;
        }

        // 返回克隆，避免原Mat被修改
        return frame.clone();
    }

    /**
     * 获取单个摄像头的 MJPEG 帧数据（新版返回 FrameData）
     */
    public FrameData getMjpegFrameData(int cameraIndex) {
        if (cameraIndex < 0 || cameraIndex >= streamQueues.size()) {
            return null;
        }

        try {
            return streamQueues.get(cameraIndex).poll(MAX_FRAME_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 获取单个摄像头的 MJPEG 帧数据（兼容旧版）
     */
    public byte[] getMjpegFrame(int cameraIndex) {
        FrameData frameData = getMjpegFrameData(cameraIndex);
        return frameData != null ? frameData.getData() : null;
    }

    /**
     * 获取优化的流媒体帧数据（缩放 + 低质量）
     * 用于多摄像头预览，减少带宽和卡顿
     *
     * @param cameraIndex 摄像头索引
     * @param scale 缩放比例 (0.1-1.0)，例如 0.25 表示缩放到1/4
     * @param quality JPEG质量 (1-100)
     * @return 优化的JPEG帧数据
     */
    public byte[] getOptimizedStreamFrame(int cameraIndex, double scale, int quality) {
        if (cameraIndex < 0 || cameraIndex >= currentFrames.size()) {
            return null;
        }

        Mat frame = currentFrames.get(cameraIndex);
        if (frame == null || frame.empty()) {
            return null;
        }

        Mat scaledFrame = null;
        MatOfByte mob = null;
        try {
            // 缩放图像
            if (scale < 1.0) {
                int newWidth = (int) (frame.cols() * scale);
                int newHeight = (int) (frame.rows() * scale);
                scaledFrame = new Mat();
                org.opencv.imgproc.Imgproc.resize(frame, scaledFrame, new org.opencv.core.Size(newWidth, newHeight),
                    org.opencv.imgproc.Imgproc.INTER_AREA);
            } else {
                scaledFrame = frame;
            }

            // 编码为JPEG（使用指定质量）
            mob = new MatOfByte();
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality);
            if (Imgcodecs.imencode(".jpg", scaledFrame, mob, params)) {
                return mob.toArray();
            }
            return null;
        } catch (Exception e) {
            logger.error("Failed to get optimized stream frame for camera {}", cameraIndex, e);
            return null;
        } finally {
            if (scaledFrame != null && scaledFrame != frame) {
                scaledFrame.release();
            }
            if (mob != null) {
                mob.release();
            }
        }
    }

    /**
     * 获取所有摄像头的当前帧（用于预览，带同步检查）
     */
    public List<FrameResult> getAllCameraFramesBase64WithSync() {
        List<FrameResult> results = new ArrayList<>();
        long referenceTime = System.currentTimeMillis();
        
        for (int i = 0; i < Math.min(currentFrames.size(), cameraSources.size()); i++) {
            String frame = getCameraImageBase64(i);
            results.add(new FrameResult(i, frame, referenceTime));
        }
        return results;
    }

    /**
     * 帧结果封装类
     */
    public static class FrameResult {
        private final int index;
        private final String frame;
        private final long timestamp;

        public FrameResult(int index, String frame, long timestamp) {
            this.index = index;
            this.frame = frame;
            this.timestamp = timestamp;
        }

        public int getIndex() { return index; }
        public String getFrame() { return frame; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * 获取所有摄像头的当前帧（兼容旧版）
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
     * 获取同步的多摄像头帧（按序列号匹配）
     */
    public List<SyncFrameData> getSynchronizedFrames() {
        List<SyncFrameData> result = new ArrayList<>();
        
        for (int i = 0; i < streamQueues.size(); i++) {
            BlockingQueue<FrameData> queue = streamQueues.get(i);
            FrameData frameData = queue.peek();
            if (frameData != null) {
                result.add(new SyncFrameData(i, frameData));
            }
        }
        
        return result;
    }

    /**
     * 同步帧数据结构
     */
    public static class SyncFrameData {
        private final int cameraIndex;
        private final byte[] data;
        private final long timestamp;
        private final long sequence;

        public SyncFrameData(int cameraIndex, FrameData frameData) {
            this.cameraIndex = cameraIndex;
            this.data = frameData.getData();
            this.timestamp = frameData.getTimestamp();
            this.sequence = frameData.getSequence();
        }

        public int getCameraIndex() { return cameraIndex; }
        public byte[] getData() { return data; }
        public long getTimestamp() { return timestamp; }
        public long getSequence() { return sequence; }
    }

    /**
     * 获取拼接后的图像（用于检测）
     */
    public Mat getStitchedImage() {
        List<Mat> frames = new ArrayList<>();
        for (int i = 0; i < cameraSources.size(); i++) {
            Mat frame = currentFrames.get(i);
            if (frame != null && !frame.empty()) {
                frames.add(frame.clone());
            }
        }
        if (frames.isEmpty() || frames.size() != cameraSources.size()) {
            return null;
        }
        
        Mat stitched = null;
        try {
            if (frames.size() >= 2) {
                StitchStrategy stitchStrategy = (StitchStrategy) stitchConfigService.getStitchStrategy();
                stitched = stitchStrategy.stitch(frames);
            } else {
                stitched = frames.get(0).clone();
            }
            return stitched;
        } catch (Exception e) {
            logger.error("Failed to stitch images", e);
            return null;
        } finally {
            for (Mat frame : frames) {
                if (frame != null) frame.release();
            }
        }
    }

    /**
     * 获取拼接后的图像（Base64 格式）
     */
    public String getStitchedImageBase64() {
        Mat stitched = getStitchedImage();
        if (stitched == null) {
            return null;
        }
        
        MatOfByte mob = null;
        try {
            mob = new MatOfByte();
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
            if (Imgcodecs.imencode(".jpg", stitched, mob, params)) {
                byte[] bytes = mob.toArray();
                return Base64.getEncoder().encodeToString(bytes);
            }
            return null;
        } catch (Exception e) {
            logger.error("Failed to encode stitched image to base64", e);
            return null;
        } finally {
            if (mob != null) mob.release();
            if (stitched != null) stitched.release();
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
        if (frames.isEmpty() || frames.size() != cameraSources.size()) {
            return null;
        }
        
        Mat stitched = null;
        MatOfByte mob = null;
        try {
            if (frames.size() >= 2) {
                StitchStrategy stitchStrategy = (StitchStrategy) stitchConfigService.getStitchStrategy();
                stitched = stitchStrategy.stitch(frames);
            } else {
                stitched = frames.get(0);
            }
            
            mob = new MatOfByte();
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
            if (Imgcodecs.imencode(".jpg", stitched, mob, params)) {
                return mob.toArray();
            }
            return null;
        } catch (Exception e) {
            logger.error("Failed to stitch images for MJPEG", e);
            return null;
        } finally {
            if (mob != null) mob.release();
            if (stitched != null && frames.size() >= 2) stitched.release();
            for (Mat frame : frames) {
                if (frame != null) frame.release();
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

    /**
     * 获取当前帧率（基于全局序列号）
     */
    public int getCurrentFps() {
        return TARGET_FPS;
    }

    /**
     * 获取当前队列状态（用于监控）
     */
    public List<Integer> getQueueSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (BlockingQueue<FrameData> queue : streamQueues) {
            sizes.add(queue.size());
        }
        return sizes;
    }
}
