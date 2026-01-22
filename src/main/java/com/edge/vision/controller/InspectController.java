package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.infer.YOLOInferenceEngine;
import com.edge.vision.core.template.model.DetectedObject;
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
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.time.LocalDateTime;
import java.util.List;
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

            // 类型识别逻辑 + 获取实际图像尺寸 + 检测工件整体（使用类型识别引擎）
            Mat stitchedMat = base64ToMat(stitchedImageBase64);
            List<List<Double>> workpieceCorners = null;
            List<Double> workpieceBbox = null;

            if (!stitchedMat.empty()) {
                imageShape = new int[]{
                        stitchedMat.rows(),
                        stitchedMat.cols(),
                        stitchedMat.channels()
                };
                logger.debug("Actual image shape: [h={}, w={}, c={}]", imageShape[0], imageShape[1], imageShape[2]);

                // 检测工件整体并获取工件类型（使用类型识别引擎）
                if (typeInferenceEngine != null) {
                    try {
                        List<Detection> typeDetections = typeInferenceEngine.predict(stitchedMat);
                        if (!typeDetections.isEmpty()) {
                            // 取置信度最高的检测结果作为工件整体
                            Detection bestDetection = typeDetections.stream()
                                    .max(Comparator.comparing(Detection::getConfidence))
                                    .orElse(null);

                            if (bestDetection != null) {
                                // 获取工件类型
                                if (bestDetection.getConfidence() > 0.5) {
                                    suggestedType = bestDetection.getLabel();
                                    logger.info("Suggested type: {} (confidence: {})", suggestedType, bestDetection.getConfidence());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Type model detection failed", e);
                    }
                } else {
                    logger.debug("Type inference engine not available, skipping workpiece detection");
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
            long decodeStart = System.currentTimeMillis();
            Mat stitchedMat = base64ToMat(preCheckData.getStitchedImage());
            long decodeTime = System.currentTimeMillis() - decodeStart;
            logger.info("Base64 decode time: {} ms, Image size: {}x{}", decodeTime, stitchedMat.width(), stitchedMat.height());

            long inferenceStart = System.currentTimeMillis();
            List<Detection> detailDetections = detailInferenceEngine.predict(stitchedMat);
            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            logger.info("YOLO inference time: {} ms, Detections found: {}", inferenceTime, detailDetections.size());

            // 根据配置的 match-strategy 选择匹配方法
            QualityStandardService.QualityEvaluationResult evaluationResult = null;
            List<DetectedObject> detectedObjects = convertDetectionsToDetectedObjects(detailDetections);

            try {
                // 直接调用 evaluateWithTemplate，内部会根据 match-strategy 选择对应 Matcher
                evaluationResult = qualityStandardService.evaluateWithTemplate(
                    request.getConfirmedPartName(), detectedObjects);

                // 如果返回了模板比对结果，说明使用了新模式
                if (evaluationResult.getTemplateComparisons() != null &&
                    !evaluationResult.getTemplateComparisons().isEmpty()) {
                    logger.info("Using template-based evaluation for part type: {}",
                        request.getConfirmedPartName());
                }
            } catch (Exception e) {
                logger.warn("Template-based evaluation failed: {}", e.getMessage());
            }

            // 绘制检测结果（包含模板比对结果）
            long drawStart = System.currentTimeMillis();
            Mat resultMat = drawInspectionResults(stitchedMat.clone(), detailDetections, evaluationResult);
            long drawTime = System.currentTimeMillis() - drawStart;
            logger.info("Draw results time: {} ms", drawTime);

            long encodeStart = System.currentTimeMillis();
            String resultImageBase64 = matToBase64(resultMat);
            long encodeTime = System.currentTimeMillis() - encodeStart;
            logger.info("Image encode time (images): {} ms", encodeTime);

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

            // 将模板比对结果添加到 analysis 中
            if (evaluationResult != null && evaluationResult.getTemplateComparisons() != null &&
                !evaluationResult.getTemplateComparisons().isEmpty()) {
                analysis.setTemplateComparisons(evaluationResult.getTemplateComparisons());
            }

            analysis.setQualityStatus(evaluationResult == null || !evaluationResult.isPassed() ? "FAIL" : "PASS");
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

            // 质检结果
            inspectionEntity.setPassed(evaluationResult.isPassed());
            inspectionEntity.setQualityStatus(evaluationResult.isPassed() ? "PASS" : "FAIL");
            inspectionEntity.setQualityMessage(evaluationResult.getMessage());

            // 使用的模板
            if (evaluationResult.getTemplateComparisons() != null &&
                !evaluationResult.getTemplateComparisons().isEmpty()) {
                inspectionEntity.setTemplateId(request.getConfirmedPartName());
            }

            // 元数据
            Map<String, Object> meta = new HashMap<>();
            meta.put("defectCount", analysis.getDefectCount());
            meta.put("details", analysis.getDetails());
            meta.put("qualityStatus", analysis.getQualityStatus());
            // 保存模板比对详细结果
            if (evaluationResult.getTemplateComparisons() != null) {
                meta.put("templateComparisons", evaluationResult.getTemplateComparisons());
            }
            if (evaluationResult.getProcessingTimeMs() != null) {
                meta.put("processingTimeMs", evaluationResult.getProcessingTimeMs());
            }
            inspectionEntity.setMeta(meta);

            long saveStart = System.currentTimeMillis();
            dataManager.saveRecord(inspectionEntity, resultImageBase64);
            long saveTime = System.currentTimeMillis() - saveStart;
            logger.info("Save record time: {} ms", saveTime);

            response.put("status", "success");
            response.put("data", data);

            stitchedMat.release();
            resultMat.release();

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
                    查询历史检测记录，支持按时间范围、批次ID过滤和分页。

                    **查询参数**：

                    | 参数 | 类型 | 必填 | 说明 |
                    |------|------|------|------|
                    | startDate | string | 否 | 开始日期，格式：YYYY-MM-DD |
                    | endDate | string | 否 | 结束日期，格式：YYYY-MM-DD |
                    | batchId | string | 否 | 批次ID |
                    | page | number | 否 | 页码，从1开始，默认1 |
                    | pageSize | number | 否 | 每页数量，默认20 |

                    **使用示例**：
                    - 查询所有记录（分页）：`GET /api/inspect/records?page=1&pageSize=20`
                    - 查询特定日期：`GET /api/inspect/records?startDate=2024-01-15&endDate=2024-01-15`
                    - 查询日期范围：`GET /api/inspect/records?startDate=2024-01-01&endDate=2024-01-31`
                    - 查询特定批次：`GET /api/inspect/records?batchId=BATCH-001`
                    - 组合查询：`GET /api/inspect/records?startDate=2024-01-01&endDate=2024-01-31&batchId=BATCH-001&page=1&pageSize=10`

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
                                              "data": {
                                                "records": [
                                                  {
                                                    "id": "550e8400-e29b-41d4-a716-446655440000",
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
                                                "pagination": {
                                                  "page": 1,
                                                  "pageSize": 20,
                                                  "total": 100,
                                                  "totalPages": 5,
                                                  "hasNext": true,
                                                  "hasPrevious": false
                                                }
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getRecords(
            @Parameter(
                    description = "开始时间（格式：YYYY-MM-DD 或 YYYY-MM-DDTHH:mm:ss）",
                    example = "2024-01-01T10:30:00"
            )
            @RequestParam(required = false) String startDateTime,

            @Parameter(
                    description = "结束时间（格式：YYYY-MM-DD 或 YYYY-MM-DDTHH:mm:ss）",
                    example = "2024-01-31T18:00:00"
            )
            @RequestParam(required = false) String endDateTime,

            @Parameter(
                    description = "批次ID",
                    example = "BATCH-2024-001"
            )
            @RequestParam(required = false) String batchId,

            @Parameter(
                    description = "页码（从1开始）",
                    example = "1"
            )
            @RequestParam(required = false, defaultValue = "1") Integer page,

            @Parameter(
                    description = "每页数量",
                    example = "20"
            )
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {

        Map<String, Object> response = new HashMap<>();

        try {
            // 解析时间参数 - 支持日期和日期时间格式
            java.time.LocalDateTime start = null;
            java.time.LocalDateTime end = null;

            if (startDateTime != null && !startDateTime.isEmpty()) {
                try {
                    // 尝试解析为日期时间格式 (YYYY-MM-DDTHH:mm:ss)
                    start = java.time.LocalDateTime.parse(startDateTime);
                } catch (Exception e) {
                    try {
                        // 如果失败，尝试解析为日期格式 (YYYY-MM-DD)，并设置为当天开始时间
                        java.time.LocalDate date = java.time.LocalDate.parse(startDateTime);
                        start = date.atStartOfDay();
                    } catch (Exception e2) {
                        response.put("status", "error");
                        response.put("message", "Invalid startDateTime format. Use YYYY-MM-DD or YYYY-MM-DDTHH:mm:ss");
                        return ResponseEntity.badRequest().body(response);
                    }
                }
            }

            if (endDateTime != null && !endDateTime.isEmpty()) {
                try {
                    // 尝试解析为日期时间格式 (YYYY-MM-DDTHH:mm:ss)
                    end = java.time.LocalDateTime.parse(endDateTime);
                } catch (Exception e) {
                    try {
                        // 如果失败，尝试解析为日期格式 (YYYY-MM-DD)，并设置为当天结束时间
                        java.time.LocalDate date = java.time.LocalDate.parse(endDateTime);
                        end = date.atTime(23, 59, 59);
                    } catch (Exception e2) {
                        response.put("status", "error");
                        response.put("message", "Invalid endDateTime format. Use YYYY-MM-DD or YYYY-MM-DDTHH:mm:ss");
                        return ResponseEntity.badRequest().body(response);
                    }
                }
            }

            // 参数校验
            if (page == null || page < 1) {
                page = 1;
            }
            if (pageSize == null || pageSize < 1 || pageSize > 100) {
                pageSize = 20;
            }

            // 查询记录 - 使用支持精确时间的方法
            com.edge.vision.service.DataManager.PageResult pageResult =
                dataManager.queryRecords(start, end, batchId, page, pageSize);

            // 构建响应
            Map<String, Object> pagination = new HashMap<>();
            pagination.put("page", pageResult.getPage());
            pagination.put("pageSize", pageResult.getPageSize());
            pagination.put("total", pageResult.getTotal());
            pagination.put("totalPages", pageResult.getTotalPages());
            pagination.put("hasNext", pageResult.hasNext());
            pagination.put("hasPrevious", pageResult.hasPrevious());

            Map<String, Object> data = new HashMap<>();
            data.put("records", pageResult.getData());
            data.put("pagination", pagination);

            response.put("status", "success");
            response.put("data", data);

            return ResponseEntity.ok(response);

        } catch (java.time.format.DateTimeParseException e) {
            response.put("status", "error");
            response.put("message", "日期格式错误，请使用 YYYY-MM-DD 格式");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
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

    /**
     * 将 Detection 列表转换为 DetectedObject 列表
     * 用于模板比对模式
     */
    private List<DetectedObject> convertDetectionsToDetectedObjects(List<Detection> detections) {
        List<DetectedObject> result = new ArrayList<>();
        for (Detection detection : detections) {
            DetectedObject obj = new DetectedObject();
            obj.setClassName(detection.getLabel());
            obj.setClassId(detection.getClassId());
            obj.setConfidence(detection.getConfidence());

            // 从 bbox 计算 center 和 width/height
            float[] bbox = detection.getBbox();
            if (bbox != null && bbox.length >= 4) {
                double centerX = (bbox[0] + bbox[2]) / 2.0;
                double centerY = (bbox[1] + bbox[3]) / 2.0;
                double width = bbox[2] - bbox[0];
                double height = bbox[3] - bbox[1];

                obj.setCenter(new com.edge.vision.core.template.model.Point(centerX, centerY));
                obj.setWidth(width);
                obj.setHeight(height);
            }

            result.add(obj);
        }
        return result;
    }


    private Mat base64ToMat(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_COLOR);
    }

    private String matToBase64(Mat mat) {
        MatOfByte mob = new MatOfByte();
        // 使用 JPEG 质量 80（默认 95），降低质量可加快编码速度
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 80);
        Imgcodecs.imencode(".jpg", mat, mob, params);
        byte[] bytes = mob.toArray();
        mob.release();
        params.release();
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 绘制质检结果（包含模板比对结果）
     * 性能优化：只做一次 Mat <-> BufferedImage 转换，所有绘制在 Graphics2D 上完成
     *
     * @param image 原始图像
     * @param detections 检测结果
     * @param evaluationResult 模板比对结果
     * @return 绘制后的图像
     */
    private Mat drawInspectionResults(Mat image, List<Detection> detections,
                                      QualityStandardService.QualityEvaluationResult evaluationResult) {
        // 一次性 Mat -> BufferedImage 转换
        BufferedImage bufferedImage = matToBufferedImage(image);

        Graphics2D g2d = bufferedImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));

        // 1. 绘制所有检测结果（绿色框）
        for (Detection detection : detections) {
            drawDetectionBox(g2d, detection);
        }

        // 2. 绘制模板比对结果
        if (evaluationResult != null && evaluationResult.getTemplateComparisons() != null) {
            for (QualityStandardService.QualityEvaluationResult.TemplateComparison comp : evaluationResult.getTemplateComparisons()) {
                switch (comp.getStatus()) {
                    case MISSING -> drawMissingAnnotation(g2d, comp);
                    case EXTRA -> drawExtraAnnotation(g2d, comp);
                    case PASSED -> drawPassedAnnotation(g2d, comp);
                    case DEVIATION_EXCEEDED -> drawDeviationAnnotation(g2d, comp);
                }
            }
        }

        // 3. 左上角整体结果绘制已移除（按用户要求）

        g2d.dispose();

        // BufferedImage -> Mat 转换（覆盖原 Mat）
        byte[] data = ((DataBufferByte) bufferedImage.getRaster().getDataBuffer()).getData();
        image.put(0, 0, data);

        return image;
    }

    /**
     * 绘制单个检测框（使用 Graphics2D）
     */
    private void drawDetectionBox(Graphics2D g2d, Detection detection) {
        float[] bbox = detection.getBbox();
        if (bbox == null || bbox.length < 4) return;

        int x1 = (int) bbox[0];
        int y1 = (int) bbox[1];
        int x2 = (int) bbox[2];
        int y2 = (int) bbox[3];

        // 绘制绿色框
        g2d.setColor(Color.GREEN);
        g2d.drawRect(x1, y1, x2 - x1, y2 - y1);

        // 绘制标签
        String label = String.format("%s: %.2f", detection.getLabel(), detection.getConfidence());
        int textY = Math.max(y1 - 5, 15);
        g2d.drawString(label, x1, textY);
    }

    /**
     * 绘制漏检标注（红色虚线框 + 中心十字 + 文字）
     */
    private void drawMissingAnnotation(Graphics2D g2d, QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;
        int size = 30;

        g2d.setColor(Color.RED);

        // 绘制虚线框
        int x1 = (int) (x - size), y1 = (int) (y - size);
        int x2 = (int) (x + size), y2 = (int) (y + size);
        g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1.0f, new float[]{10, 5}, 0));
        g2d.drawRect(x1, y1, x2 - x1, y2 - y1);

        // 绘制中心十字
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine((int) x - 10, (int) y, (int) x + 10, (int) y);
        g2d.drawLine((int) x, (int) y - 10, (int) x, (int) y + 10);

        // 绘制文字
        g2d.setStroke(new BasicStroke(1));
        g2d.drawString("漏检: " + comp.getFeatureName(), x1, y1 - 10);
    }

    /**
     * 绘制错检标注（红色X + 外圈 + 文字）
     */
    private void drawExtraAnnotation(Graphics2D g2d, QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;
        int size = 25;

        g2d.setColor(Color.RED);
        g2d.setStroke(new BasicStroke(3));

        // 绘制红色X
        g2d.drawLine((int) x - size, (int) y - size, (int) x + size, (int) y + size);
        g2d.drawLine((int) x + size, (int) y - size, (int) x - size, (int) y + size);

        // 绘制外圈
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval((int) x - size - 5, (int) y - size - 5, (size + 5) * 2, (size + 5) * 2);

        // 绘制文字
        g2d.setStroke(new BasicStroke(1));
        g2d.drawString("错检", (int) x - size, (int) y - size - 10);
    }

    /**
     * 绘制合格标注（绿色小方块）
     */
    private void drawPassedAnnotation(Graphics2D g2d, QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;
        int size = 8;

        g2d.setColor(Color.GREEN);
        g2d.fillRect((int) x - size, (int) y - size, size * 2, size * 2);
    }

    /**
     * 绘制偏差标注（黄色空心框）
     */
    private void drawDeviationAnnotation(Graphics2D g2d, QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;
        int size = 10;

        g2d.setColor(Color.YELLOW);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRect((int) x - size, (int) y - size, size * 2, size * 2);
    }

    /**
     * Mat 转换为 BufferedImage
     */
    private BufferedImage matToBufferedImage(Mat mat) {
        int type = BufferedImage.TYPE_3BYTE_BGR;
        if (mat.channels() == 1) {
            type = BufferedImage.TYPE_BYTE_GRAY;
        } else if (mat.channels() == 4) {
            type = BufferedImage.TYPE_4BYTE_ABGR;
        }

        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), type);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat.get(0, 0, data);
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
