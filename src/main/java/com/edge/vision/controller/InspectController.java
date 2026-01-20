package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.infer.YOLOInferenceEngine;
import com.edge.vision.core.quality.FeatureComparison;
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
import org.opencv.core.Core;
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

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
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

            // 根据质量检测标准判断 PASS/FAIL
            // 首先尝试使用新的模板比对模式（如果配置了模板）
            QualityStandardService.QualityEvaluationResult evaluationResult = null;
            List<DetectedObject> detectedObjects = convertDetectionsToDetectedObjects(detailDetections);

            try {
                // 尝试使用模板比对模式
                evaluationResult = qualityStandardService.evaluateWithTemplate(
                    request.getConfirmedPartName(), detectedObjects);

                // 如果返回了模板比对结果，说明使用了新模式
                if (evaluationResult.getTemplateComparisons() != null &&
                    !evaluationResult.getTemplateComparisons().isEmpty()) {
                    logger.info("Using template-based evaluation for part type: {}",
                        request.getConfirmedPartName());
                }
            } catch (Exception e) {
                logger.warn("Template-based evaluation failed, falling back to traditional: {}",
                    e.getMessage());
            }

            // 绘制检测结果（包含模板比对结果）
            // 注释：前端使用 index.html 绘制，后端绘制代码保留备用
            Mat resultMat = drawInspectionResults(stitchedMat.clone(), detailDetections, evaluationResult);
            String resultImageBase64 = matToBase64(stitchedMat.clone());
            String resultImageBase642 = matToBase64(resultMat);

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

            dataManager.saveRecord(inspectionEntity, resultImageBase642);

            response.put("status", "success");
            response.put("data", data);

            stitchedMat.release();
            resultMat.release();  // 注释：后端绘制已禁用，前端使用 index.html 绘制

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

    /**
     * 绘制质检结果（包含模板比对结果）
     *
     * @param image 原始图像
     * @param detections 检测结果
     * @param evaluationResult 模板比对结果
     * @return 绘制后的图像
     */
    private Mat drawInspectionResults(Mat image, List<Detection> detections,
                                      QualityStandardService.QualityEvaluationResult evaluationResult) {
        // 1. 先绘制所有检测结果（绿色框）
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

        // 2. 如果有模板比对结果，绘制漏检和错检
        if (evaluationResult != null && evaluationResult.getTemplateComparisons() != null) {
            for (QualityStandardService.QualityEvaluationResult.TemplateComparison comp : evaluationResult.getTemplateComparisons()) {
                switch (comp.getStatus()) {
                    case MISSING:
                        // 漏检：红色虚线框 + 文字
                        drawMissingAnnotation(image, comp);
                        break;
                    case EXTRA:
                        // 错检：红色X + 文字
                        drawExtraAnnotation(image, comp);
                        break;
                    case PASSED:
                        // 合格：蓝色小框标记
                        drawPassedAnnotation(image, comp);
                        break;
                    case DEVIATION_EXCEEDED:
                        // 偏差过大：黄色小框标记
                        drawDeviationAnnotation(image, comp);
                        break;
                    default:
                        break;
                }
            }
        }

        // 3. 在左上角绘制整体结果
        drawOverallResult(image, evaluationResult);

        return image;
    }

    /**
     * 绘制漏检标注（红色虚线框）
     */
    private void drawMissingAnnotation(Mat image, QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        // 位置（已经是检测图坐标）
        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;

        // 绘制红色虚线框（表示这里应该有特征）
        Scalar red = new Scalar(0, 0, 255);
        int size = 30; // 框的大小
        Point p1 = new Point(x - size, y - size);
        Point p2 = new Point(x + size, y + size);

        // 绘制虚线框
        drawDashedRectangle(image, p1, p2, red, 2);

        // 绘制中心十字
        Point cross1 = new Point(x - 10, y);
        Point cross2 = new Point(x + 10, y);
        Point cross3 = new Point(x, y - 10);
        Point cross4 = new Point(x, y + 10);
        Imgproc.line(image, cross1, cross2, red, 2);
        Imgproc.line(image, cross3, cross4, red, 2);

        // 绘制文字标签（使用中文绘制）
        String label = "漏检: " + comp.getFeatureName();
        Point textPos = new Point(x - size, y - size - 10);
        drawChineseText(image, label, textPos, red, 14);
    }

    /**
     * 绘制错检标注（红色X）
     */
    private void drawExtraAnnotation(Mat image, QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;

        Scalar red = new Scalar(0, 0, 255);
        int size = 25;

        // 绘制红色X
        Point p1 = new Point(x - size, y - size);
        Point p2 = new Point(x + size, y + size);
        Point p3 = new Point(x + size, y - size);
        Point p4 = new Point(x - size, y + size);

        Imgproc.line(image, p1, p2, red, 3);
        Imgproc.line(image, p3, p4, red, 3);

        // 绘制外圈
        Point center = new Point(x, y);
        Imgproc.circle(image, center, size + 5, red, 2);

        // 绘制文字标签（使用中文绘制）
        String label = "错检";
        Point textPos = new Point(x - size, y - size - 10);
        drawChineseText(image, label, textPos, red, 14);
    }

    /**
     * 绘制合格标注（绿色小框）
     */
    private void drawPassedAnnotation(Mat image, QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;

        Scalar green = new Scalar(0, 255, 0); // 绿色（合格）
        int size = 8;

        // 绘制绿色小方块标记
        Point p1 = new Point(x - size, y - size);
        Point p2 = new Point(x + size, y + size);
        Imgproc.rectangle(image, p1, p2, green, -1); // 填充
    }

    /**
     * 绘制偏差标注（黄色小框）
     */
    private void drawDeviationAnnotation(Mat image, QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;

        Scalar yellow = new Scalar(0, 255, 255);
        int size = 10;

        // 绘制黄色空心框标记
        Point p1 = new Point(x - size, y - size);
        Point p2 = new Point(x + size, y + size);
        Imgproc.rectangle(image, p1, p2, yellow, 2);
    }

    /**
     * 绘制虚线矩形
     */
    private void drawDashedRectangle(Mat image, Point p1, Point p2, Scalar color, int thickness) {
        // 虚线模式：10像素实线，5像素空白
        int[] dashPattern = {10, 5};
        int dashIndex = 0;
        int currentDash = 0;

        // 绘制上边
        drawDashedLine(image, new Point(p1.x, p1.y), new Point(p2.x, p1.y), color, thickness, dashPattern, dashIndex);
        // 绘制右边
        drawDashedLine(image, new Point(p2.x, p1.y), new Point(p2.x, p2.y), color, thickness, dashPattern, 0);
        // 绘制下边
        drawDashedLine(image, new Point(p1.x, p2.y), new Point(p2.x, p2.y), color, thickness, dashPattern, 0);
        // 绘制左边
        drawDashedLine(image, new Point(p1.x, p1.y), new Point(p1.x, p2.y), color, thickness, dashPattern, 0);
    }

    /**
     * 绘制虚线
     */
    private void drawDashedLine(Mat image, Point p1, Point p2, Scalar color, int thickness, int[] dashPattern, int dashIndex) {
        double totalDist = Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
        int dashCount = (int) (totalDist / 2); // 每2像素一段
        double dx = (p2.x - p1.x) / dashCount;
        double dy = (p2.y - p1.y) / dashCount;

        boolean draw = true;
        int segmentLength = 0;
        int patternIdx = dashIndex;

        for (int i = 0; i < dashCount; i++) {
            Point start = new Point(p1.x + dx * i, p1.y + dy * i);
            Point end = new Point(p1.x + dx * (i + 1), p1.y + dy * (i + 1));

            if (draw) {
                Imgproc.line(image, start, end, color, thickness);
            }

            segmentLength++;
            if (segmentLength >= dashPattern[patternIdx]) {
                segmentLength = 0;
                patternIdx = (patternIdx + 1) % dashPattern.length;
                draw = !draw;
            }
        }
    }

    /**
     * 在 Mat 上绘制中文文字（使用 Graphics2D）
     * OpenCV 的 putText 不支持中文，需要用 Java 的 Graphics2D
     */
    private void drawChineseText(Mat mat, String text, org.opencv.core.Point pos,
                                  org.opencv.core.Scalar color, double fontSize) {
        try {
            // 将 Mat 转换为 BufferedImage
            BufferedImage image = matToBufferedImage(mat);

            Graphics2D g2d = image.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // OpenCV Scalar 是 BGR 格式，需要转换为 RGB
            java.awt.Color awtColor = new java.awt.Color(
                (int) color.val[2],  // R
                (int) color.val[1],  // G
                (int) color.val[0]   // B
            );

            g2d.setColor(awtColor);
            g2d.setFont(new Font("Microsoft YaHei", Font.PLAIN, (int) fontSize));
            g2d.drawString(text, (int) pos.x, (int) pos.y);
            g2d.dispose();

            // 将 BufferedImage 转换回 Mat
            bufferedImageToMat(image, mat);
        } catch (Exception e) {
            logger.warn("Failed to draw Chinese text: {}", e.getMessage());
            // 降级到英文
            Imgproc.putText(mat, text, pos, Imgproc.FONT_HERSHEY_SIMPLEX,
                fontSize / 20, color, 1);
        }
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

    /**
     * BufferedImage 转换回 Mat（覆盖原 Mat）
     */
    private void bufferedImageToMat(BufferedImage image, Mat mat) {
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat.put(0, 0, data);
    }

    /**
     * 在左上角绘制整体结果
     */
    private void drawOverallResult(Mat image, QualityStandardService.QualityEvaluationResult evaluationResult) {
        if (evaluationResult == null) return;

        Scalar color;
        String statusText;

        if (evaluationResult.isPassed()) {
            color = new Scalar(0, 255, 0); // 绿色
            statusText = "PASS";
        } else {
            color = new Scalar(0, 0, 255); // 红色
            statusText = "FAIL";
        }

        // 手动计算统计信息（QualityEvaluationResult 没有 getSummary 方法）
        int passed = 0, missing = 0, deviation = 0, extra = 0;
        if (evaluationResult.getTemplateComparisons() != null) {
            for (QualityStandardService.QualityEvaluationResult.TemplateComparison comp : evaluationResult.getTemplateComparisons()) {
                if (comp.isWithinTolerance()) passed++;
                else if (comp.getStatus() == com.edge.vision.core.quality.FeatureComparison.ComparisonStatus.MISSING) missing++;
                else if (comp.getStatus() == com.edge.vision.core.quality.FeatureComparison.ComparisonStatus.EXTRA) extra++;
                else deviation++;
            }
        }

        // 绘制半透明背景
        Point bg1 = new Point(10, 10);
        Point bg2 = new Point(350, 120);
        Mat overlay = image.clone();
        Imgproc.rectangle(overlay, bg1, bg2, new Scalar(200, 200, 200), -1);
        Core.addWeighted(overlay, 0.5, image, 0.5, 0, image);
        overlay.release();

        // 绘制边框
        Imgproc.rectangle(image, bg1, bg2, color, 2);

        // 绘制文字（使用 Graphics2D 支持中文）
        int y = 30;
        String resultText = evaluationResult.isPassed() ? "检测结果: 合格" : "检测结果: 不合格";
        drawChineseText(image, resultText, new Point(20, y), color, 16);

        y += 25;
        String statsText = String.format("通过: %d  漏检: %d  偏差: %d  错检: %d",
                passed, missing, deviation, extra);
        drawChineseText(image, statsText, new Point(20, y), new Scalar(0, 0, 0), 14);

        // 绘制消息
        if (evaluationResult.getMessage() != null) {
            y += 20;
            String msg = evaluationResult.getMessage();
            if (msg.length() > 30) {
                msg = msg.substring(0, 30) + "...";
            }
            drawChineseText(image, msg, new Point(20, y), new Scalar(100, 100, 100), 12);
        }
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
