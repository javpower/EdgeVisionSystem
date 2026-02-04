package com.edge.vision.controller;

import com.edge.vision.service.CameraService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 摄像头控制器
 *
 * 提供摄像头管理和视频流传输功能
 */
@RestController
@RequestMapping("/api/camera")
@Tag(name = "摄像头管理", description = "摄像头启动、停止、状态查询及视频流传输")
public class CameraController {
    private static final Logger logger = LoggerFactory.getLogger(CameraController.class);

    // 流控参数
    private static final int TARGET_FPS = 25; // 目标帧率（略低于采集帧率）
    private static final int FRAME_INTERVAL_MS = 1000 / TARGET_FPS; // 40ms
    private static final int MIN_FRAME_INTERVAL_MS = 20; // 最小间隔
    private static final int MAX_FRAME_INTERVAL_MS = 100; // 最大间隔
    private static final int STREAM_TIMEOUT_MS = 0; // 流超时时间（0 表示不超时，持续推送）

    @Autowired
    private CameraService cameraService;

    // ================== 基础控制接口 ==================

    /**
     * 启动摄像头
     */
    @PostMapping("/start")
    @Operation(
            summary = "启动摄像头",
            description = """
                    启动所有配置的摄像头并开始采集图像。

                    **注意事项**：
                    - 摄像头来源在 application.yml 中配置
                    - 支持本地摄像头索引（0, 1, 2...）或 RTSP URL
                    - 启动后摄像头会以约 30FPS 的频率采集图像
                    - 多摄像头会同步启动，确保帧同步

                    **配置示例**：
                    ```yaml
                    # 使用本地摄像头
                    edge-vision:
                      cameras:
                        sources: [0, 1]

                    # 使用 RTSP 流
                    edge-vision:
                      cameras:
                        sources: ["rtsp://192.168.1.100:554/stream", "rtsp://192.168.1.101:554/stream"]
                    ```
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "摄像头启动成功",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CameraStartResponse.class),
                            examples = @ExampleObject(
                                    name = "成功示例",
                                    value = """
                                            {
                                              "status": "success",
                                              "cameraCount": 2
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "未找到可用摄像头"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务器内部错误"
            )
    })
    public ResponseEntity<Map<String, Object>> startCamera() {
        Map<String, Object> response = new HashMap<>();
        try {
            long startTime = System.currentTimeMillis();
            cameraService.startCameras();
            int count = cameraService.getCameraCount();
            long duration = System.currentTimeMillis() - startTime;
            
            if (count == 0) {
                response.put("status", "warning");
                response.put("message", "未找到可用摄像头");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            
            response.put("status", "success");
            response.put("cameraCount", count);
            response.put("startupTimeMs", duration);
            response.put("targetFps", TARGET_FPS);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Start failed", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 停止摄像头
     */
    @PostMapping("/stop")
    @Operation(summary = "停止摄像头", description = "停止所有正在运行的摄像头，释放资源")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "摄像头已停止"
            )
    })
    public ResponseEntity<Map<String, Object>> stopCamera() {
        Map<String, Object> response = new HashMap<>();
        try {
            cameraService.stopCameras();
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取摄像头状态
     */
    @GetMapping("/status")
    @Operation(
            summary = "获取摄像头状态",
            description = "获取当前摄像头的运行状态和数量信息"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "状态查询成功"
            )
    })
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        
        data.put("running", cameraService.isRunning());
        data.put("cameraCount", cameraService.getCameraCount());
        data.put("configuredCount", cameraService.getConfiguredCameraCount());
        data.put("targetFps", cameraService.getCurrentFps());
        data.put("streamTargetFps", TARGET_FPS);
        
        if (cameraService.isRunning()) {
            data.put("queueSizes", cameraService.getQueueSizes());
        }
        
        response.put("status", "success");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    // ================== 视频流核心接口 (MJPEG) ==================

    /**
     * 单个摄像头视频流（优化版）
     */
    @GetMapping(value = "/stream/{cameraIndex}")
    @Operation(
            summary = "获取单个摄像头视频流",
            description = """
                    获取指定摄像头的 MJPEG 视频流（优化版）。

                    **优化特性**：
                    - 自适应帧率控制，保证流畅性
                    - 连接保活机制，自动检测断开
                    - 智能缓冲，减少卡顿

                    **使用方式**：
                    ```html
                    <img src="http://localhost:8000/api/camera/stream/0" />
                    ```

                    **注意事项**：
                    - 需要先调用 /start 接口启动摄像头
                    - cameraIndex 从 0 开始
                    - 流会持续推送直到客户端断开连接
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "MJPEG 视频流",
                    content = @Content(mediaType = "multipart/x-mixed-replace; boundary=frame")
            ),
            @ApiResponse(responseCode = "503", description = "摄像头未运行")
    })
    public void streamCamera(
            @Parameter(description = "摄像头索引（从 0 开始）", required = true, example = "0")
            @PathVariable int cameraIndex,
            HttpServletResponse response) throws IOException {
        
//        if (!cameraService.isRunning()) {
//            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Cameras not running");
//            return;
//        }
//
//        if (cameraIndex < 0 || cameraIndex >= cameraService.getCameraCount()) {
//            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid camera index");
//            return;
//        }
//
//        // 设置响应头
//        response.setContentType("multipart/x-mixed-replace; boundary=frame");
//        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
//        response.setHeader("Pragma", "no-cache");
//        response.setHeader("Expires", "0");
//        response.setHeader("Connection", "keep-alive");
//
//        OutputStream out = response.getOutputStream();
//        AtomicBoolean clientConnected = new AtomicBoolean(true);
//        AtomicLong frameCount = new AtomicLong(0);
//        AtomicLong lastFrameTime = new AtomicLong(System.currentTimeMillis());
//        long streamStartTime = System.currentTimeMillis();
//
//        // 保活线程 - 检测客户端是否断开
//        Thread keepAliveThread = new Thread(() -> {
//            while (clientConnected.get() && cameraService.isRunning()) {
//                try {
//                    Thread.sleep(5000); // 每5秒检查一次
//                    // 如果超过10秒没有发送帧，可能连接已断开
//                    if (System.currentTimeMillis() - lastFrameTime.get() > 10000) {
//                        logger.warn("Camera {} stream timeout, closing connection", cameraIndex);
//                        clientConnected.set(false);
//                    }
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                    break;
//                }
//            }
//        }, "Stream-KeepAlive-" + cameraIndex);
//        keepAliveThread.setDaemon(true);
//        keepAliveThread.start();
//
//        try {
//            logger.info("Starting optimized stream for camera {}", cameraIndex);
//
//            while (clientConnected.get() && cameraService.isRunning()) {
//                long loopStart = System.currentTimeMillis();
//
//                // 检查流超时（仅在设置了超时时间时生效）
//                if (STREAM_TIMEOUT_MS > 0 && loopStart - streamStartTime > STREAM_TIMEOUT_MS) {
//                    logger.info("Stream timeout for camera {}", cameraIndex);
//                    break;
//                }
//
//                FrameData frameData = cameraService.getMjpegFrameData(cameraIndex);
//
//                if (frameData != null && frameData.getData() != null && frameData.getData().length > 0) {
//                    try {
//                        // 发送 MJPEG 帧
//                        out.write(("--frame\r\n").getBytes());
//                        out.write(("Content-Type: image/jpeg\r\n").getBytes());
//                        out.write(("Content-Length: " + frameData.getData().length + "\r\n").getBytes());
//                        out.write(("X-Frame-Sequence: " + frameData.getSequence() + "\r\n").getBytes());
//                        out.write(("X-Frame-Timestamp: " + frameData.getTimestamp() + "\r\n").getBytes());
//                        out.write(("\r\n").getBytes());
//                        out.write(frameData.getData());
//                        out.write(("\r\n").getBytes());
//                        out.flush();
//
//                        frameCount.incrementAndGet();
//                        lastFrameTime.set(System.currentTimeMillis());
//                    } catch (IOException e) {
//                        logger.info("Client disconnected from camera {} stream", cameraIndex);
//                        clientConnected.set(false);
//                        break;
//                    }
//                }
//
//                // 帧率控制 - 自适应间隔
//                long elapsed = System.currentTimeMillis() - loopStart;
//                long sleepTime = FRAME_INTERVAL_MS - elapsed;
//
//                if (sleepTime > MIN_FRAME_INTERVAL_MS) {
//                    Thread.sleep(sleepTime);
//                } else if (sleepTime < 0) {
//                    // 处理耗时超过目标间隔，稍微休息一下避免CPU占用过高
//                    Thread.sleep(MIN_FRAME_INTERVAL_MS);
//                }
//            }
//        } catch (Exception e) {
//            if (!(e instanceof IOException)) {
//                logger.error("Stream error for camera {}: {}", cameraIndex, e.getMessage());
//            }
//        } finally {
//            clientConnected.set(false);
//            long duration = System.currentTimeMillis() - streamStartTime;
//            long fps = duration > 0 ? (frameCount.get() * 1000 / duration) : 0;
//            logger.info("Camera {} stream ended - {} frames in {}ms (~{} FPS)",
//                    cameraIndex, frameCount.get(), duration, fps);
//        }

        //上面原始的不要删除不要修改
        streamCameraOptimized(cameraIndex,0.25,50,response);
    }

    /**
     * 优化的摄像头视频流（支持缩放和质量调整）
     * 适用于多摄像头4K场景，减少带宽和卡顿
     */
    @GetMapping(value = "/stream/{cameraIndex}/optimized")
    @Operation(
            summary = "获取优化的摄像头视频流",
            description = """
                    获取指定摄像头的优化MJPEG视频流。

                    **优化特性**：
                    - 支持分辨率缩放（默认0.25，即4K->1080p）
                    - 支持JPEG质量调整（默认50，降低质量减少带宽）
                    - 适用于多摄像头预览场景

                    **参数**：
                    - scale: 缩放比例，默认0.25（4K->1080p），0.125（4K->540p）
                    - quality: JPEG质量，默认50（范围1-100，越低越小）
                    推荐配置：
                      scale=0.25, quality=50  // 1080p @ 15fps，适合大部分场景
                      scale=0.1875, quality=40 // 720p @ 15fps，更流畅
                      scale=0.125, quality=30  // 540p @ 15fps，最低带宽                
                    **使用方式**：
                    ```html
                    <!-- 4K->1080p, 质量50 -->
                    <img src="http://localhost:8000/api/camera/stream/0/optimized" />
                    <!-- 4K->720p, 质量40 -->
                    <img src="http://localhost:8000/api/camera/stream/0/optimized?scale=0.1875&quality=40" />
                    ```
                    """
    )
    public void streamCameraOptimized(
            @Parameter(description = "摄像头索引", required = true)
            @PathVariable int cameraIndex,
            @Parameter(description = "缩放比例 (默认0.25，即4K->1080p)")
            @RequestParam(defaultValue = "0.25") double scale,
            @Parameter(description = "JPEG质量 (1-100, 默认50)")
            @RequestParam(defaultValue = "50") int quality,
            HttpServletResponse response) throws IOException {

        if (!cameraService.isRunning()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Cameras not running");
            return;
        }

        if (cameraIndex < 0 || cameraIndex >= cameraService.getCameraCount()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid camera index");
            return;
        }

        // 参数校验
        scale = Math.max(0.05, Math.min(1.0, scale)); // 限制在0.05-1.0之间
        quality = Math.max(1, Math.min(100, quality)); // 限制在1-100之间

        // 设置响应头
        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("Connection", "keep-alive");

        OutputStream out = response.getOutputStream();
        AtomicBoolean clientConnected = new AtomicBoolean(true);
        AtomicLong frameCount = new AtomicLong(0);
        AtomicLong lastFrameTime = new AtomicLong(System.currentTimeMillis());
        long streamStartTime = System.currentTimeMillis();

        // 降低帧率控制 - 优化流使用更低的帧率
        int optimizedFps = 15; // 15 FPS足够预览
        long optimizedInterval = 1000 / optimizedFps;

        logger.info("Starting optimized stream for camera {}: scale={}, quality={}, fps={}",
            cameraIndex, scale, quality, optimizedFps);

        try {
            while (clientConnected.get() && cameraService.isRunning()) {
                long loopStart = System.currentTimeMillis();

                // 获取优化的帧数据
                byte[] jpegBytes = cameraService.getOptimizedStreamFrame(cameraIndex, scale, quality);

                if (jpegBytes != null && jpegBytes.length > 0) {
                    try {
                        // 发送 MJPEG 帧
                        out.write(("--frame\r\n").getBytes());
                        out.write(("Content-Type: image/jpeg\r\n").getBytes());
                        out.write(("Content-Length: " + jpegBytes.length + "\r\n").getBytes());
                        out.write(("\r\n").getBytes());
                        out.write(jpegBytes);
                        out.write(("\r\n").getBytes());
                        out.flush();

                        frameCount.incrementAndGet();
                        lastFrameTime.set(System.currentTimeMillis());
                    } catch (IOException e) {
                        logger.info("Client disconnected from camera {} optimized stream", cameraIndex);
                        clientConnected.set(false);
                        break;
                    }
                }

                // 帧率控制
                long elapsed = System.currentTimeMillis() - loopStart;
                long sleepTime = optimizedInterval - elapsed;

                if (sleepTime > 10) {
                    Thread.sleep(sleepTime);
                } else if (sleepTime < 0) {
                    Thread.sleep(10);
                }
            }
        } catch (Exception e) {
            if (!(e instanceof IOException)) {
                logger.error("Optimized stream error for camera {}: {}", cameraIndex, e.getMessage());
            }
        } finally {
            clientConnected.set(false);
            long duration = System.currentTimeMillis() - streamStartTime;
            long fps = duration > 0 ? (frameCount.get() * 1000 / duration) : 0;
            logger.info("Camera {} optimized stream ended - {} frames in {}ms (~{} FPS)",
                    cameraIndex, frameCount.get(), duration, fps);
        }
    }

    /**
     * 获取单个摄像头当前帧（用于前端 Canvas 拼接）
     */
    @GetMapping(value = "/frame/{cameraIndex}")
    @Operation(
            summary = "获取单个摄像头当前帧",
            description = "获取指定摄像头的当前帧（Base64 编码的 JPEG 图片）"
    )
    public ResponseEntity<Map<String, Object>> getCameraFrame(
            @Parameter(description = "摄像头索引（从 0 开始）", required = true, example = "0")
            @PathVariable int cameraIndex) {
        Map<String, Object> response = new HashMap<>();
        try {
            String base64Frame = cameraService.getCameraImageBase64(cameraIndex);
            if (base64Frame == null) {
                response.put("status", "error");
                response.put("message", "Camera not available or no frame");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("index", cameraIndex);
            data.put("frame", "data:image/jpeg;base64," + base64Frame);
            data.put("timestamp", System.currentTimeMillis());

            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get camera frame", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取所有摄像头当前帧（同步版）
     */
    @GetMapping(value = "/frames")
    @Operation(
            summary = "获取所有摄像头当前帧（同步版）",
            description = """
                    一次性获取所有摄像头的当前帧，带同步时间戳。

                    **优化特性**：
                    - 所有帧使用统一的时间戳
                    - 适合前端 Canvas 拼接
                    - 减少多摄像头画面不同步问题
                    """
    )
    public ResponseEntity<Map<String, Object>> getAllCameraFrames() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<CameraService.FrameResult> frameResults = cameraService.getAllCameraFramesBase64WithSync();
            List<Map<String, Object>> frames = new ArrayList<>();
            long syncTimestamp = System.currentTimeMillis();

            for (CameraService.FrameResult result : frameResults) {
                Map<String, Object> frameData = new HashMap<>();
                frameData.put("index", result.getIndex());
                if (result.getFrame() != null) {
                    frameData.put("frame", "data:image/jpeg;base64," + result.getFrame());
                } else {
                    frameData.put("frame", null);
                }
                frameData.put("timestamp", syncTimestamp);
                frames.add(frameData);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("cameraCount", frames.size());
            data.put("frames", frames);
            data.put("syncTimestamp", syncTimestamp);

            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get all camera frames", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取同步的多摄像头帧（按序列号）
     */
    @GetMapping(value = "/frames/sync")
    @Operation(
            summary = "获取同步的多摄像头帧",
            description = """
                    获取按序列号同步的多摄像头帧。

                    **适用场景**：
                    - 需要精确帧同步的应用
                    - 多摄像头拼接时减少画面撕裂
                    """
    )
    public ResponseEntity<Map<String, Object>> getSynchronizedFrames() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<CameraService.SyncFrameData> syncFrames = cameraService.getSynchronizedFrames();
            List<Map<String, Object>> frames = new ArrayList<>();
            
            long minSequence = Long.MAX_VALUE;
            for (CameraService.SyncFrameData frame : syncFrames) {
                if (frame.getSequence() < minSequence) {
                    minSequence = frame.getSequence();
                }
            }

            for (CameraService.SyncFrameData frame : syncFrames) {
                Map<String, Object> frameData = new HashMap<>();
                frameData.put("index", frame.getCameraIndex());
                frameData.put("frame", "data:image/jpeg;base64," + 
                        java.util.Base64.getEncoder().encodeToString(frame.getData()));
                frameData.put("timestamp", frame.getTimestamp());
                frameData.put("sequence", frame.getSequence());
                frameData.put("syncOffset", frame.getSequence() - minSequence);
                frames.add(frameData);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("cameraCount", frames.size());
            data.put("frames", frames);
            data.put("referenceSequence", minSequence);

            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get synchronized frames", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 拼接后的全景视频流（优化版）
     */
    @GetMapping(value = "/stream")
    @Operation(
            summary = "获取拼接后的全景视频流",
            description = "获取多个摄像头拼接后的全景 MJPEG 视频流（优化版）"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "拼接后的 MJPEG 视频流",
                    content = @Content(mediaType = "multipart/x-mixed-replace; boundary=frame")
            ),
            @ApiResponse(responseCode = "503", description = "摄像头未运行或数量不足")
    })
    public void streamStitched(HttpServletResponse response) throws IOException {
        if (!cameraService.isRunning() || cameraService.getCameraCount() < 1) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Cameras not available");
            return;
        }

        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("Connection", "keep-alive");

        OutputStream out = response.getOutputStream();
        AtomicBoolean clientConnected = new AtomicBoolean(true);
        AtomicLong frameCount = new AtomicLong(0);
        long streamStartTime = System.currentTimeMillis();

        try {
            logger.info("Starting optimized stitched stream");
            
            while (clientConnected.get() && cameraService.isRunning()) {
                long loopStart = System.currentTimeMillis();
                
                // 检查流超时（仅在设置了超时时间时生效）
                if (STREAM_TIMEOUT_MS > 0 && loopStart - streamStartTime > STREAM_TIMEOUT_MS) {
                    logger.info("Stitched stream timeout");
                    break;
                }

                byte[] frameData = cameraService.getStitchedMjpegFrame();

                if (frameData != null && frameData.length > 0) {
                    try {
                        out.write(("--frame\r\n").getBytes());
                        out.write(("Content-Type: image/jpeg\r\n").getBytes());
                        out.write(("Content-Length: " + frameData.length + "\r\n").getBytes());
                        out.write(("\r\n").getBytes());
                        out.write(frameData);
                        out.write(("\r\n").getBytes());
                        out.flush();

                        frameCount.incrementAndGet();
                    } catch (IOException e) {
                        logger.info("Client disconnected from stitched stream");
                        clientConnected.set(false);
                        break;
                    }
                }

                // 帧率控制
                long elapsed = System.currentTimeMillis() - loopStart;
                long sleepTime = FRAME_INTERVAL_MS - elapsed;
                
                if (sleepTime > MIN_FRAME_INTERVAL_MS) {
                    Thread.sleep(sleepTime);
                } else if (sleepTime < 0) {
                    Thread.sleep(MIN_FRAME_INTERVAL_MS);
                }
            }
        } catch (Exception e) {
            if (!(e instanceof IOException)) {
                logger.error("Stitched stream error: {}", e.getMessage());
            }
        } finally {
            long duration = System.currentTimeMillis() - streamStartTime;
            long fps = duration > 0 ? (frameCount.get() * 1000 / duration) : 0;
            logger.info("Stitched stream ended - {} frames in {}ms (~{} FPS)", 
                    frameCount.get(), duration, fps);
        }
    }

    // ================== DTO 类 ==================

    @Schema(description = "摄像头启动响应")
    public static class CameraStartResponse {
        @Schema(description = "状态", example = "success")
        public String status;

        @Schema(description = "成功启动的摄像头数量", example = "2")
        public int cameraCount;
    }
}
