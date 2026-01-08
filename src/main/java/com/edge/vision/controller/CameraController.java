package com.edge.vision.controller;

import com.edge.vision.service.CameraService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/camera")
//@CrossOrigin(origins = "*") // 如果你是前后端分离开发（如用VSCode Live Server），请取消注释这行
public class CameraController {
    private static final Logger logger = LoggerFactory.getLogger(CameraController.class);

    @Autowired
    private CameraService cameraService;

    // ================== 基础控制接口 ==================

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startCamera() {
        Map<String, Object> response = new HashMap<>();
        try {
            cameraService.startCameras();
            int count = cameraService.getCameraCount();
            if (count == 0) {
                response.put("status", "warning");
                response.put("message", "未找到可用摄像头");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            response.put("status", "success");
            response.put("cameraCount", count);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Start failed", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/stop")
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

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        data.put("running", cameraService.isRunning());
        data.put("cameraCount", cameraService.getCameraCount());
        response.put("status", "success");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    // ================== 视频流核心接口 (MJPEG) ==================

    /**
     * 单个摄像头流
     */
    @GetMapping(value = "/stream/{cameraIndex}")
    public void streamCamera(@PathVariable int cameraIndex, HttpServletResponse response) throws IOException {
        if (!cameraService.isRunning()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Cameras not running");
            return;
        }

        // 1. 设置响应头 (关键：MJPEG 格式 + 禁用缓存)
        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        OutputStream out = response.getOutputStream();

        try {
            while (cameraService.isRunning()) {
                // 获取一帧 JPEG 数据
                byte[] frameData = cameraService.getMjpegFrame(cameraIndex);

                if (frameData != null && frameData.length > 0) {
                    try {
                        // 2. 写入 MJPEG 协议数据包
                        out.write(("--frame\r\n").getBytes());
                        out.write(("Content-Type: image/jpeg\r\n").getBytes());
                        out.write(("Content-Length: " + frameData.length + "\r\n").getBytes());
                        out.write(("\r\n").getBytes());
                        out.write(frameData);
                        out.write(("\r\n").getBytes());

                        // 3. 强制刷新：如果客户端断开，这一步会抛出 IOException
                        out.flush();

                    } catch (IOException e) {
                        logger.info("客户端已断开摄像头 {} 的连接", cameraIndex);
                        break; // 退出循环
                    }
                } else {
                    // 没有数据时短暂休眠，避免 CPU 100%
                    Thread.sleep(30);
                }
            }
        } catch (Exception e) {
            logger.error("Stream error: " + e.getMessage());
        }
    }

    /**
     * 拼接后的全景流
     */
    @GetMapping(value = "/stream")
    public void streamStitched(HttpServletResponse response) throws IOException {
        if (!cameraService.isRunning() || cameraService.getCameraCount() < 2) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Cannot start stitched stream");
            return;
        }

        // 1. 设置响应头
        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        OutputStream out = response.getOutputStream();

        try {
            while (cameraService.isRunning()) {
                // 获取拼接后的 JPEG 数据
                byte[] frameData = cameraService.getStitchedMjpegFrame();

                if (frameData != null && frameData.length > 0) {
                    try {
                        // 2. 写入数据
                        out.write(("--frame\r\n").getBytes());
                        out.write(("Content-Type: image/jpeg\r\n").getBytes());
                        out.write(("Content-Length: " + frameData.length + "\r\n").getBytes());
                        out.write(("\r\n").getBytes());
                        out.write(frameData);
                        out.write(("\r\n").getBytes());

                        // 3. 强制刷新
                        out.flush();

                    } catch (IOException e) {
                        logger.info("客户端已断开拼接流的连接");
                        break;
                    }
                } else {
                    Thread.sleep(50); // 拼接可能比较慢，稍微多睡一会
                }
            }
        } catch (Exception e) {
            logger.error("Stitched stream error: " + e.getMessage());
        }
    }
}