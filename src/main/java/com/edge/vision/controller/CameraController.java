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

    @Autowired
    private CameraService cameraService;

    // ================== 基础控制接口 ==================

    /**
     * 启动摄像头
     *
     * 启动所有配置的摄像头。摄像头来源在 application.yml 中配置：
     * ```yaml
     * edge-vision:
     *   cameras:
     *     sources: [0, 1]  # 摄像头索引或 RTSP URL
     * ```
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
                    - 如果摄像头被占用或未连接，启动会失败

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
                    description = "未找到可用摄像头",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "无摄像头",
                                    value = """
                                            {
                                              "status": "warning",
                                              "message": "未找到可用摄像头"
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务器内部错误"
            )
    })
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

    /**
     * 停止摄像头
     *
     * 停止所有正在运行的摄像头，释放资源
     */
    @PostMapping("/stop")
    @Operation(
            summary = "停止摄像头",
            description = """
                    停止所有正在运行的摄像头并释放资源。

                    **注意事项**：
                    - 停止后需要重新调用 /start 接口才能再次使用摄像头
                    - 停止操作会释放摄像头设备，允许其他程序使用
                    - 视频流连接会自动断开
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "摄像头已停止",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success"
                                            }
                                            """
                            )
                    )
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
     *
     * 查询摄像头运行状态和数量信息
     */
    @GetMapping("/status")
    @Operation(
            summary = "获取摄像头状态",
            description = """
                    获取当前摄像头的运行状态和数量信息。

                    **返回字段说明**：
                    | 字段 | 类型 | 说明 |
                    |------|------|------|
                    | running | boolean | 摄像头是否正在运行 |
                    | cameraCount | number | 成功启动的摄像头数量 |
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "状态查询成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "running": true,
                                                "cameraCount": 2
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
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
     * 单个摄像头视频流
     *
     * 返回指定摄像头的 MJPEG 视频流
     * 可直接在浏览器中打开或使用 video 标签播放
     */
    @GetMapping(value = "/stream/{cameraIndex}")
    @Operation(
            summary = "获取单个摄像头视频流",
            description = """
                    获取指定摄像头的 MJPEG 视频流。

                    **使用方式**：

                    1. **浏览器直接访问**：
                    ```
                    http://localhost:8000/api/camera/stream/0
                    ```

                    2. **HTML img 标签**：
                    ```html
                    <img src="http://localhost:8000/api/camera/stream/0" />
                    ```

                    3. **HTML video 标签**（部分浏览器）：
                    ```html
                    <video src="http://localhost:8000/api/camera/stream/0" />
                    ```

                    **注意事项**：
                    - 返回的是 MJPEG 流，不是 MP4 文件
                    - 需要先调用 /start 接口启动摄像头
                    - cameraIndex 从 0 开始，对应配置中 sources 的索引
                    - 流会持续推送直到客户端断开连接
                    - 建议使用 HTTP 协议访问，HTTPS 可能有兼容性问题
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "MJPEG 视频流",
                    content = @Content(
                            mediaType = "multipart/x-mixed-replace; boundary=frame"
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "摄像头未运行"
            )
    })
    public void streamCamera(
            @Parameter(
                    description = "摄像头索引（从 0 开始）",
                    required = true,
                    example = "0",
                    schema = @Schema(type = "integer", minimum = "0")
            )
            @PathVariable int cameraIndex,
            HttpServletResponse response) throws IOException {
        if (!cameraService.isRunning()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Cameras not running");
            return;
        }

        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        OutputStream out = response.getOutputStream();

        try {
            while (cameraService.isRunning()) {
                byte[] frameData = cameraService.getMjpegFrame(cameraIndex);

                if (frameData != null && frameData.length > 0) {
                    try {
                        out.write(("--frame\r\n").getBytes());
                        out.write(("Content-Type: image/jpeg\r\n").getBytes());
                        out.write(("Content-Length: " + frameData.length + "\r\n").getBytes());
                        out.write(("\r\n").getBytes());
                        out.write(frameData);
                        out.write(("\r\n").getBytes());
                        out.flush();
                    } catch (IOException e) {
                        logger.info("客户端已断开摄像头 {} 的连接", cameraIndex);
                        break;
                    }
                } else {
                    Thread.sleep(30);
                }
            }
        } catch (Exception e) {
            logger.error("Stream error: " + e.getMessage());
        }
    }

    /**
     * 拼接后的全景视频流
     *
     * 返回多个摄像头拼接后的 MJPEG 视频流
     * 拼接策略由配置决定
     */
    @GetMapping(value = "/stream")
    @Operation(
            summary = "获取拼接后的全景视频流",
            description = """
                    获取多个摄像头拼接后的全景 MJPEG 视频流。

                    **使用方式**：

                    1. **浏览器直接访问**：
                    ```
                    http://localhost:8000/api/camera/stream
                    ```

                    2. **HTML img 标签**：
                    ```html
                    <img src="http://localhost:8000/api/camera/stream" />
                    ```

                    **拼接策略**：

                    拼接策略由配置文件决定，可通过 `/api/stitch/strategy` 接口动态切换：

                    | 策略 | 说明 |
                    |------|------|
                    | simple | 简单水平拼接，速度快 |
                    | auto | 自动特征点检测拼接，效果更好 |
                    | manual | 手动调节拼接参数 |

                    **注意事项**：
                    - 至少需要 2 个摄像头才能拼接
                    - 拼接会增加处理延迟，约 50-100ms
                    - 手动拼接模式可通过 `/api/stitch/manual` 接口调节参数
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "拼接后的 MJPEG 视频流",
                    content = @Content(
                            mediaType = "multipart/x-mixed-replace; boundary=frame"
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "摄像头未运行或摄像头数量不足"
            )
    })
    public void streamStitched(HttpServletResponse response) throws IOException {
        if (!cameraService.isRunning() || cameraService.getCameraCount() < 2) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Cannot start stitched stream");
            return;
        }

        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        OutputStream out = response.getOutputStream();

        try {
            while (cameraService.isRunning()) {
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
                    } catch (IOException e) {
                        logger.info("客户端已断开拼接流的连接");
                        break;
                    }
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            logger.error("Stitched stream error: " + e.getMessage());
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
