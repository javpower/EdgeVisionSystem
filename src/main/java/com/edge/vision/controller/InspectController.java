package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.infer.YOLOInferenceEngine;
import com.edge.vision.model.*;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.DataManager;
import com.edge.vision.service.QualityStandardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缺陷检测控制器
 *
 * 提供工件预检、确认检测、记录查询等功能
 */
@RestController
@RequestMapping("/api/inspect")
@Tag(name = "缺陷检测", description = "工件缺陷预检、确认检测及记录管理")
public class InspectController {
    private static final Logger logger = LoggerFactory.getLogger(InspectController.class);

    @Autowired
    private YamlConfig config;

    @Autowired
    private CameraService cameraService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private QualityStandardService qualityStandardService;

    // 类型识别引擎（可选）
    private YOLOInferenceEngine typeInferenceEngine;

    // 细节检测引擎（必须）
    private YOLOInferenceEngine detailInferenceEngine;

    // 临时存储预检数据
    private final Map<String, PreCheckData> preCheckStore = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 初始化类型识别引擎（可选）
        if (config.getModels().getTypeModel() != null && !config.getModels().getTypeModel().isEmpty()) {
            try {
                typeInferenceEngine = new YOLOInferenceEngine(
                        config.getModels().getTypeModel(),
                        config.getModels().getConfThres(),
                        config.getModels().getIouThres(),
                        config.getModels().getDevice()
                );
                logger.info("Type inference engine initialized successfully");
            } catch (Exception e) {
                logger.warn("Failed to initialize type inference engine: {}", e.getMessage());
            }
        }

        // 初始化细节检测引擎（必须）
        if (config.getModels().getDetailModel() != null && !config.getModels().getDetailModel().isEmpty()) {
            try {
                detailInferenceEngine = new YOLOInferenceEngine(
                        config.getModels().getDetailModel(),
                        config.getModels().getConfThres(),
                        config.getModels().getIouThres(),
                        config.getModels().getDevice(),
                        1280, 1280
                );
                logger.info("Detail inference engine initialized successfully");
            } catch (Exception e) {
                logger.warn("Failed to initialize detail inference engine: {}", e.getMessage());
            }
        } else {
            logger.warn("Detail model not configured - detection features will be disabled");
        }
    }

    /**
     * 预检接口
     */
    @PostMapping("/pre-check")
    @Operation(
            summary = "预检工件",
            description = """
                    捕获当前拼接图像并进行类型识别（如果配置了类型识别模型）。

                    **功能说明**：
                    1. 捕获当前多摄像头拼接后的图像
                    2. 如果配置了类型识别模型，自动识别工件类型
                    3. 返回预览图像和建议的工件类型
                    4. 生成 requestId 用于后续确认检测

                    **使用流程**：
                    ```
                    1. 调用 /pre-check 获取预览和 requestId
                    2. 前端显示预览图像给用户确认
                    3. 用户确认后调用 /confirm 进行正式检测
                    ```

                    **注意事项**：
                    - 需要先启动摄像头
                    - requestId 有效期为一次确认检测
                    - 确认检测后 requestId 失效
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "预检成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "requestId": "550e8400-e29b-41d4-a716-446655440000",
                                                "suggestedType": "EKS",
                                                "previewImage": "data:image/jpeg;base64,/9j/4AAQ...",
                                                "cameraCount": 2,
                                                "imageShape": [720, 1280, 3]
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "摄像头未启动",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "error",
                                              "message": "Cameras are not running. Please start cameras first."
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
    public ResponseEntity<Map<String, Object>> preCheck() {
        Map<String, Object> response = new HashMap<>();
        logger.info("=== Pre-Check Request ===");

        try {
            if (!cameraService.isRunning()) {
                response.put("status", "error");
                response.put("message", "Cameras are not running. Please start cameras first.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            String stitchedImageBase64 = cameraService.getStitchedImageBase64();
            if (stitchedImageBase64 == null) {
                response.put("status", "error");
                response.put("message", "Failed to capture stitched image.");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            String requestId = UUID.randomUUID().toString();
            String suggestedType = null;
            int[] imageShape = new int[]{720, 1280, 3};

            // 类型识别逻辑 + 获取实际图像尺寸
            Mat stitchedMat = base64ToMat(stitchedImageBase64);
            if (!stitchedMat.empty()) {
                imageShape = new int[]{
                        stitchedMat.rows(),
                        stitchedMat.cols(),
                        stitchedMat.channels()
                };
                logger.debug("Actual image shape: [h={}, w={}, c={}]", imageShape[0], imageShape[1], imageShape[2]);
            }

            if (typeInferenceEngine != null) {
                try {
                    List<Detection> typeDetections = typeInferenceEngine.predict(stitchedMat);
                    if (!typeDetections.isEmpty()) {
                        Detection bestDetection = typeDetections.stream()
                                .max(Comparator.comparing(Detection::getConfidence))
                                .orElse(null);
                        if (bestDetection != null && bestDetection.getConfidence() > 0.5) {
                            suggestedType = bestDetection.getLabel();
                            logger.info("Suggested type: {} (confidence: {})", suggestedType, bestDetection.getConfidence());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Type detection failed", e);
                }
            }
            stitchedMat.release();

            PreCheckData preCheckData = new PreCheckData();
            preCheckData.setStitchedImage(stitchedImageBase64);
            preCheckData.setTimestamp(LocalDateTime.now());
            preCheckStore.put(requestId, preCheckData);

            PreCheckResponse preCheckResponse = new PreCheckResponse();
            preCheckResponse.setRequestId(requestId);
            preCheckResponse.setSuggestedType(suggestedType);
            preCheckResponse.setPreviewImage("data:image/jpeg;base64," + stitchedImageBase64);
            preCheckResponse.setCameraCount(cameraService.getCameraCount());
            preCheckResponse.setImageShape(imageShape);

            response.put("status", "success");
            response.put("data", preCheckResponse);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Pre-check failed", e);
            response.put("status", "error");
            response.put("message", "Pre-check failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 确认检测接口
     */
    @PostMapping("/confirm")
    @Operation(
            summary = "确认检测",
            description = """
                    基于预检的图像进行缺陷检测和质量评估。

                    **功能说明**：
                    1. 使用预检时捕获的图像进行缺陷检测
                    2. 返回检测到的缺陷详情
                    3. 根据配置的质量标准判断 PASS/FAIL
                    4. 绘制缺陷标注图像
                    5. 保存检测记录

                    **质量标准配置**：
                    在 application.yml 中配置：
                    ```yaml
                    edge-vision:
                      quality-standards:
                        EKS:
                          hole: 1      # 允许1个孔洞
                          nut: 0       # 不允许螺母缺陷
                    ```

                    **检测流程**：
                    ```
                    预检获取 requestId
                        ↓
                    用户确认工件类型和批次
                        ↓
                    调用 /confirm 进行检测
                        ↓
                    返回缺陷详情和质量判断
                        ↓
                    自动保存检测记录
                    ```
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "确认检测请求",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ConfirmRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "requestId": "550e8400-e29b-41d4-a716-446655440000",
                                      "confirmedPartName": "EKS",
                                      "batchId": "BATCH-2024-001",
                                      "operator": "张三"
                                    }
                                    """
                    )
            )
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "检测完成",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "batchInfo": {
                                                  "partName": "EKS",
                                                  "batchId": "BATCH-2024-001",
                                                  "operator": "张三"
                                                },
                                                "analysis": {
                                                  "defectCount": 2,
                                                  "qualityStatus": "FAIL",
                                                  "details": [
                                                    {
                                                      "label": "hole",
                                                      "confidence": 0.92,
                                                      "bbox": [100, 200, 150, 250]
                                                    },
                                                    {
                                                      "label": "nut",
                                                      "confidence": 0.88,
                                                      "bbox": [300, 400, 350, 450]
                                                    }
                                                  ]
                                                },
                                                "deviceId": "EDGE_001",
                                                "timestamp": 1704067200,
                                                "resultImage": "data:image/jpeg;base64,/9j/4AAQ..."
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求参数错误或 requestId 无效"
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "检测引擎未初始化"
            )
    })
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody ConfirmRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (request.getRequestId() == null || request.getConfirmedPartName() == null || request.getBatchId() == null) {
                response.put("status", "error");
                response.put("message", "Missing required fields");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            if (detailInferenceEngine == null) {
                response.put("status", "error");
                response.put("message", "Detail inference engine not available.");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }

            PreCheckData preCheckData = preCheckStore.remove(request.getRequestId());
            if (preCheckData == null) {
                response.put("status", "error");
                response.put("message", "Invalid request ID or request expired");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // 执行检测
            Mat stitchedMat = base64ToMat(preCheckData.getStitchedImage());
            List<Detection> detailDetections = detailInferenceEngine.predict(stitchedMat);

            // 绘制检测结果
//            Mat resultMat = drawDetections(stitchedMat.clone(), detailDetections);
            String resultImageBase64 = matToBase64(stitchedMat);

            // 构建结果
            ConfirmResponse.ConfirmData data = new ConfirmResponse.ConfirmData();

            ConfirmResponse.BatchInfo batchInfo = new ConfirmResponse.BatchInfo();
            batchInfo.setPartName(request.getConfirmedPartName());
            batchInfo.setBatchId(request.getBatchId());
            batchInfo.setOperator(request.getOperator());
            data.setBatchInfo(batchInfo);

            ConfirmResponse.AnalysisResult analysis = new ConfirmResponse.AnalysisResult();
            analysis.setDefectCount(detailDetections.size());
            analysis.setDetails(detailDetections);

            // 根据质量检测标准判断 PASS/FAIL（使用新的质检标准服务）
            QualityStandardService.QualityEvaluationResult evaluationResult =
                qualityStandardService.evaluate(request.getConfirmedPartName(), detailDetections);
            analysis.setQualityStatus(evaluationResult.isPassed() ? "PASS" : "FAIL");
            data.setAnalysis(analysis);

            data.setDeviceId(config.getSystem().getDeviceId());
            data.setTimestamp(System.currentTimeMillis() / 1000);
            data.setResultImage("data:image/jpeg;base64," + resultImageBase64);

            // 保存记录
            InspectionEntity inspectionEntity = new InspectionEntity();
            inspectionEntity.setDeviceId(config.getSystem().getDeviceId());
            inspectionEntity.setBatchId(request.getBatchId());
            inspectionEntity.setPartName(request.getConfirmedPartName());
            inspectionEntity.setOperator(request.getOperator());
            inspectionEntity.setTimestamp(LocalDateTime.now());

            Map<String, Object> meta = new HashMap<>();
            meta.put("defectCount", analysis.getDefectCount());
            meta.put("details", analysis.getDetails());
            meta.put("qualityStatus", analysis.getQualityStatus());
            inspectionEntity.setMeta(meta);

            dataManager.saveRecord(inspectionEntity, resultImageBase64);

            response.put("status", "success");
            response.put("data", data);

            stitchedMat.release();
//            resultMat.release();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Confirm inspection failed", e);
            response.put("status", "error");
            response.put("message", "Inspection failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 查询检测记录
     */
    @GetMapping("/records")
    @Operation(
            summary = "查询检测记录",
            description = """
                    查询历史检测记录，支持按日期和批次ID过滤。

                    **查询参数**：

                    | 参数 | 类型 | 必填 | 说明 |
                    |------|------|------|------|
                    | date | string | 否 | 日期，格式：YYYY-MM-DD |
                    | batchId | string | 否 | 批次ID |
                    | limit | number | 否 | 返回记录数量限制 |

                    **使用示例**：
                    - 查询所有记录：`GET /api/inspect/records`
                    - 查询特定日期：`GET /api/inspect/records?date=2024-01-15`
                    - 查询特定批次：`GET /api/inspect/records?batchId=BATCH-001`
                    - 限制返回数量：`GET /api/inspect/records?limit=10`

                    **记录存储位置**：`data/records/` 目录
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "查询成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": [
                                                {
                                                  "deviceId": "EDGE_001",
                                                  "batchId": "BATCH-2024-001",
                                                  "partName": "EKS",
                                                  "operator": "张三",
                                                  "timestamp": "2024-01-15T10:30:00",
                                                  "meta": {
                                                    "defectCount": 2,
                                                    "qualityStatus": "FAIL"
                                                  }
                                                }
                                              ],
                                              "total": 1
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getRecords(
            @Parameter(
                    description = "日期（格式：YYYY-MM-DD）",
                    example = "2024-01-15"
            )
            @RequestParam(required = false) String date,

            @Parameter(
                    description = "批次ID",
                    example = "BATCH-2024-001"
            )
            @RequestParam(required = false) String batchId,

            @Parameter(
                    description = "返回记录数量限制",
                    example = "10"
            )
            @RequestParam(required = false) Integer limit) {

        Map<String, Object> response = new HashMap<>();

        try {
            java.time.LocalDate localDate = null;
            if (date != null) {
                localDate = java.time.LocalDate.parse(date);
            }

            java.util.List<InspectionEntity> records = dataManager.queryRecords(localDate, batchId, limit);

            response.put("status", "success");
            response.put("data", records);
            response.put("total", records.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to query records", e);
            response.put("status", "error");
            response.put("message", "Query failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取系统统计信息
     */
    @GetMapping("/stats")
    @Operation(
            summary = "获取系统统计信息",
            description = """
                    获取系统运行状态和统计数据。

                    **返回字段说明**：

                    | 字段 | 类型 | 说明 |
                    |------|------|------|
                    | totalInspections | number | 总检测次数 |
                    | passCount | number | 合格数量 |
                    | failCount | number | 不合格数量 |
                    | passRate | number | 合格率 |
                    | cameraCount | number | 摄像头数量 |
                    | running | boolean | 摄像头是否运行 |
                    | typeModelEnabled | boolean | 类型识别是否启用 |
                    | detailModelEnabled | boolean | 缺陷检测是否启用 |
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "获取成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "totalInspections": 150,
                                                "passCount": 135,
                                                "failCount": 15,
                                                "passRate": 0.9,
                                                "cameraCount": 2,
                                                "running": true,
                                                "typeModelEnabled": true,
                                                "detailModelEnabled": true
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, Object> stats = dataManager.getStatistics();
            stats.put("cameraCount", cameraService.getCameraCount());
            stats.put("running", cameraService.isRunning());
            stats.put("typeModelEnabled", typeInferenceEngine != null);
            stats.put("detailModelEnabled", detailInferenceEngine != null);

            response.put("status", "success");
            response.put("data", stats);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to get statistics", e);
            response.put("status", "error");
            response.put("message", "Failed to get statistics: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // 工具方法
    private Mat base64ToMat(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_COLOR);
    }

    private String matToBase64(Mat mat) {
        MatOfByte mob = new MatOfByte();
        Imgcodecs.imencode(".jpg", mat, mob);
        return Base64.getEncoder().encodeToString(mob.toArray());
    }

    private Mat drawDetections(Mat image, List<Detection> detections) {
        for (Detection detection : detections) {
            float[] bbox = detection.getBbox();

            if (bbox != null && bbox.length >= 4) {
                Point p1 = new Point(bbox[0], bbox[1]);
                Point p2 = new Point(bbox[2], bbox[3]);

                Imgproc.rectangle(image, p1, p2, new Scalar(0, 255, 0), 2);

                String label = String.format("%s: %.2f", detection.getLabel(), detection.getConfidence());
                double textY = Math.max(bbox[1] - 5, 15);

                Imgproc.putText(image, label, new Point(bbox[0], textY),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(0, 255, 0), 2);
            }
        }
        return image;
    }

    @PreDestroy
    public void cleanup() {
        if (typeInferenceEngine != null) {
            typeInferenceEngine.close();
        }
        if (detailInferenceEngine != null) {
            detailInferenceEngine.close();
        }
    }

    // 内部类
    private static class PreCheckData {
        private String stitchedImage;
        private LocalDateTime timestamp;

        public String getStitchedImage() { return stitchedImage; }
        public void setStitchedImage(String stitchedImage) { this.stitchedImage = stitchedImage; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    }
}
