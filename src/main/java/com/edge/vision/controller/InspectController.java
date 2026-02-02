package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.quality.MatchStrategy;
import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.model.*;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.DataManager;
import com.edge.vision.service.InferenceEngineService;
import com.edge.vision.service.QualityStandardService;
import com.edge.vision.util.VisionTool;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Autowired(required = false)
    private TemplateManager templateManager;

    @Autowired
    private InferenceEngineService inferenceEngineService;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    // 临时存储预检数据
    private final Map<String, PreCheckData> preCheckStore = new ConcurrentHashMap<>();

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

            // 获取拼接图像（增加重试次数和延迟）
            Mat stitchedMat = null;
            for (int i = 0; i < 10; i++) {
                stitchedMat = cameraService.getStitchedImage();
                if (stitchedMat != null && !stitchedMat.empty()) {
                    break;
                }
                // 等待50ms让摄像头准备新帧
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stitchedMat == null || stitchedMat.empty()) {
                response.put("status", "error");
                response.put("message", "无法获取图像，请检查摄像头是否正常运行");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            String requestId = UUID.randomUUID().toString();
            String suggestedType = null;
            int[] imageShape = new int[]{
                    stitchedMat.rows(),
                    stitchedMat.cols(),
                    stitchedMat.channels()
            };
            logger.debug("Actual image shape: [h={}, w={}, c={}]", imageShape[0], imageShape[1], imageShape[2]);

            // 类型识别逻辑 + 检测工件整体（使用类型识别引擎）
            if (inferenceEngineService.isTypeEngineAvailable()) {
                try {
                    List<Detection> typeDetections = inferenceEngineService.getTypeInferenceEngine().predict(stitchedMat);
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

            // 转换为 base64 用于返回
            String stitchedImageBase64 = matToBase64(stitchedMat);
            stitchedMat.release();

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
            if (request.getConfirmedPartName() == null) {
                response.put("status", "error");
                response.put("message", "Missing required fields");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            if (!inferenceEngineService.isDetailEngineAvailable()) {
                response.put("status", "error");
                response.put("message", "Detail inference engine not available.");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            // 获取拼接图像（增加重试次数和延迟）
            Mat stitchedMat = null;
            for (int i = 0; i < 10; i++) {
                stitchedMat = cameraService.getStitchedImage();
                if (stitchedMat != null && !stitchedMat.empty()) {
                    break;
                }
                // 等待50ms让摄像头准备新帧
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stitchedMat == null || stitchedMat.empty()) {
                response.put("status", "error");
                response.put("message", "无法获取图像，请检查摄像头是否正常运行");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            logger.info("Image size: {}x{}", stitchedMat.width(), stitchedMat.height());

            long inferenceStart = System.currentTimeMillis();
            List<Detection> detailDetections;
            QualityStandardService.QualityEvaluationResult evaluationResult = null;  // 提前声明
            // 检查是否使用 croparea 模式
            MatchStrategy strategy = config.getInspection().getMatchStrategy();
            List<DetectedObject> templateObjects=null;
            if (strategy == MatchStrategy.CROP_AREA &&
                    inferenceEngineService.isDetailEngineAvailable()) {
                logger.info("Using CROP_AREA match strategy");
                // 2. 加载 croparea 模板（从模板系统获取 objectTemplatePath）
                Template template = templateManager.load(request.getConfirmedPartName());
                if (template == null || template.getMetadata() == null) {
                    response.put("status", "error");
                    response.put("message", "CropArea template not found for: " + request.getConfirmedPartName());
                    stitchedMat.release();
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
                }
                // 3. 使用 ObjectDetectionUtil 检测工件位置
                templateObjects = VisionTool.calculateTemplateCoordinates(template, stitchedMat);
                if (!(templateObjects.size()>0)) {
                    response.put("status", "error");
                    response.put("message", "工件检测失败");
                    stitchedMat.release();
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
                }
                long templateObjectsTime = System.currentTimeMillis() - inferenceStart;
                logger.info("TemplateCoordinates time: {} ms",templateObjectsTime);
                // 5. 使用 detailInferenceEngine 识别裁剪的图像
                detailDetections = inferenceEngineService.getDetailInferenceEngine().predict(stitchedMat);
            } else {
                // 原有逻辑：直接对整图进行检测
                detailDetections = inferenceEngineService.getDetailInferenceEngine().predict(stitchedMat);
            }

            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            logger.info("YOLO inference time: {} ms, Detections found: {}", inferenceTime, detailDetections.size());

            List<DetectedObject> detectedObjects = convertDetectionsToDetectedObjects(detailDetections);
            try {
                // 直接调用 evaluateWithTemplate，传递实际裁剪尺寸用于坐标归一化
                evaluationResult = qualityStandardService.evaluateWithTemplate(
                        request.getConfirmedPartName(), detectedObjects, templateObjects);
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
     * 数据采集接口
     * 执行检测，如果失败则保存原始图片和基于模板的标注 JSON（用于训练数据收集）
     */
    @PostMapping("/collect-data")
    @Operation(
            summary = "数据采集",
            description = """
                    执行检测流程，如果检测失败则保存原始图片和基于模板的标注 JSON。

                    **功能说明**：
                    1. 走类似 /confirm 的检测流程
                    2. 检查检测结果是否 PASS
                    3. 如果 FAIL，保存：
                       - 原始图片（无标注绘制）
                       - 基于模板的标注 JSON（兼容 YOLO 训练格式）

                    **JSON 格式**：
                    ```json
                    {
                      "labels": [
                        {"name": "hole", "x1": 100, "y1": 200, "x2": 150, "y2": 250},
                        {"name": "nut", "x1": 300, "y1": 400, "x2": 350, "y2": 450}
                      ]
                    }
                    ```
                    """
    )
    public ResponseEntity<Map<String, Object>> collectData(@RequestBody com.edge.vision.model.CollectDataRequest request) {
        Map<String, Object> response = new HashMap<>();

        Mat stitchedMat = null;
        try {
            if (request.getConfirmedPartName() == null) {
                response.put("status", "error");
                response.put("message", "Missing required fields");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            if (!inferenceEngineService.isDetailEngineAvailable()) {
                response.put("status", "error");
                response.put("message", "Detail inference engine not available.");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }

            // 1. 获取拼接图像
            for (int i = 0; i < 10; i++) {
                stitchedMat = cameraService.getStitchedImage();
                if (stitchedMat != null && !stitchedMat.empty()) {
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stitchedMat == null || stitchedMat.empty()) {
                response.put("status", "error");
                response.put("message", "无法获取图像，请检查摄像头是否正常运行");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            // 2. 尝试加载模板
            String partType = request.getConfirmedPartName();
            Template template=null;
            try {
                template = templateManager.load(partType);
            }catch (IllegalArgumentException e){
                logger.warn("no template");
            }
            // 判断模板是否有效
            boolean hasValidTemplate = (template != null && template.getMetadata() != null);

            List<Detection> detailDetections = null;
            List<DetectedObject> templateObjects = null;

            // 3. 如果有模板，执行完整的检测和质量评估逻辑
            if (hasValidTemplate) {
                MatchStrategy strategy = config.getInspection().getMatchStrategy();

                // 执行推理
                if (strategy == MatchStrategy.CROP_AREA && inferenceEngineService.isDetailEngineAvailable()) {
                    templateObjects = VisionTool.calculateTemplateCoordinates(template, stitchedMat);
                    detailDetections = inferenceEngineService.getDetailInferenceEngine().predict(stitchedMat);
                } else {
                    detailDetections = inferenceEngineService.getDetailInferenceEngine().predict(stitchedMat);
                }

                // 执行质量评估
                List<DetectedObject> detectedObjects = convertDetectionsToDetectedObjects(detailDetections);
                QualityStandardService.QualityEvaluationResult evaluationResult = qualityStandardService.evaluateWithTemplate(
                        partType, detectedObjects, templateObjects);

                // 检查检测是否通过
                if (evaluationResult.isPassed()) {
                    // 检测成功，不保存数据
                    response.put("status", "success");
                    response.put("message", "检测成功，无需采集数据");
                    response.put("collected", false);
                    response.put("qualityStatus", "PASS");
                    return ResponseEntity.ok(response);
                }
                // 如果检测失败，代码继续向下执行，进入保存流程
            }

            // --- 保存数据流程 ---

            // 生成文件基础信息
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            String timeStr = now.format(formatter);
            String fileBaseName = partType + "_" + timeStr;

            // 处理保存路径（支持中文路径）
            Path collectDir;
            if (request.getSaveDir() != null && !request.getSaveDir().isEmpty()) {
                String saveDirStr = request.getSaveDir();
                logger.info("Original saveDir from request: {}", saveDirStr);
                
                // 对路径进行 URL 解码（处理前端编码的中文路径）
                try {
                    saveDirStr = java.net.URLDecoder.decode(saveDirStr, java.nio.charset.StandardCharsets.UTF_8.name());
                    logger.info("After URL decode: {}", saveDirStr);
                } catch (java.io.UnsupportedEncodingException e) {
                    logger.warn("URL decode failed for saveDir: {}", saveDirStr);
                }
                
                // 清理路径字符串
                saveDirStr = saveDirStr.trim();
                
                // 处理 Windows 路径中的反斜杠（前端传过来的可能是正斜杠或反斜杠）
                // 注意：这里不要替换，让 Paths.get 自行处理
                // saveDirStr = saveDirStr.replace('/', java.io.File.separatorChar);
                
                // 解析路径
                Path inputPath = Paths.get(saveDirStr);
                logger.info("Input path isAbsolute: {}, raw path: {}", inputPath.isAbsolute(), inputPath);
                
                if (inputPath.isAbsolute()) {
                    // 用户输入的是绝对路径，直接使用
                    collectDir = inputPath.normalize();
                } else {
                    // 用户输入的是相对路径，转换为绝对路径（基于当前工作目录）
                    collectDir = inputPath.toAbsolutePath().normalize();
                }
            } else {
                collectDir = Paths.get("data", "collected", partType).toAbsolutePath().normalize();
            }
            
            logger.info("Final collectDir: {}", collectDir);
            Files.createDirectories(collectDir);

            // 4. 图片压缩处理（如果任一边超过 6000，等比例压缩到最大边为 6000）
            Mat matToSave = resizeIfNeeded(stitchedMat);
            logger.info("Image size after resize check: {}x{}", matToSave.cols(), matToSave.rows());

            // 5. 保存原始图片 (无论有无模板都保存)
            // 使用 Java IO 保存，避免 OpenCV imwrite 在 Windows 中文路径上的问题
            String imageFileName = fileBaseName + ".jpg";
            Path imagePath = collectDir.resolve(imageFileName);
            saveMatToFile(matToSave, imagePath);
            
            // 如果创建了新的 Mat，释放它
            if (matToSave != stitchedMat) {
                matToSave.release();
            }

            String jsonPathStr = null;
            int labelCount = 0;

            // 5. 只有在有模板的情况下，才生成并保存 JSON
            if (hasValidTemplate) {
                List<Map<String, Object>> labels = new ArrayList<>();

                if (templateObjects != null) {
                    // 使用 CROP_AREA 模式计算出的坐标
                    for (DetectedObject obj : templateObjects) {
                        Map<String, Object> label = new HashMap<>();
                        label.put("name", obj.getClassName());
                        double cx = obj.getCenter().x;
                        double cy = obj.getCenter().y;
                        double w = obj.getWidth();
                        double h = obj.getHeight();
                        label.put("x1", (int) (cx - w / 2));
                        label.put("y1", (int) (cy - h / 2));
                        label.put("x2", (int) (cx + w / 2));
                        label.put("y2", (int) (cy + h / 2));
                        labels.add(label);
                    }
                } else {
                    // 非 CROP_AREA 模式，使用模板原始坐标
                    if (template.getFeatures() != null) {
                        for (com.edge.vision.core.template.model.TemplateFeature feature : template.getFeatures()) {
                            Map<String, Object> label = new HashMap<>();
                            label.put("name", feature.getName());
                            if (feature.getBbox() != null) {
                                double x = feature.getBbox().getX();
                                double y = feature.getBbox().getY();
                                double w = feature.getBbox().getWidth();
                                double h = feature.getBbox().getHeight();
                                label.put("x1", (int) x);
                                label.put("y1", (int) y);
                                label.put("x2", (int) (x + w));
                                label.put("y2", (int) (y + h));
                            }
                            labels.add(label);
                        }
                    }
                }

                // 保存 JSON 文件
                Map<String, Object> jsonData = new HashMap<>();
                jsonData.put("labels", labels);

                String jsonFileName = fileBaseName + ".json";
                Path jsonPath = collectDir.resolve(jsonFileName);
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), jsonData);

                jsonPathStr = jsonPath.toString();
                labelCount = labels.size();
            } else {
                logger.info("未找到模板 [{}]，仅保存图片，跳过 JSON 生成。", partType);
            }

            // 构建响应
            logger.info("数据采集成功: 图片={}", imagePath);
            response.put("status", "success");
            response.put("message", hasValidTemplate ? "数据采集成功" : "无模板，仅保存图片");
            response.put("collected", true);
            // 如果没有模板，状态设为 UNKNOWN 或 FAIL，视具体需求而定，这里保持 FAIL 意味着"未通过验证"
            response.put("qualityStatus", hasValidTemplate ? "FAIL" : "UNKNOWN");
            response.put("imagePath", imagePath.toString());

            if (jsonPathStr != null) {
                response.put("jsonPath", jsonPathStr);
                response.put("labelCount", labelCount);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Data collection failed", e);
            response.put("status", "error");
            response.put("message", "Data collection failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } finally {
            if (stitchedMat != null) {
                stitchedMat.release();
            }
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
            stats.put("typeModelEnabled", inferenceEngineService.isTypeEngineAvailable());
            stats.put("detailModelEnabled", inferenceEngineService.isDetailEngineAvailable());

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

                logger.info("DETECTION: label={}, bbox=[{},{},{}], center=({},{}), size={}x{}",
                    detection.getLabel(),
                    String.format("%.2f", bbox[0]),
                    String.format("%.2f", bbox[1]),
                    String.format("%.2f", bbox[2]),
                    String.format("%.2f", bbox[3]),
                    String.format("%.1f", centerX),
                    String.format("%.1f", centerY),
                    String.format("%.1f", width),
                    String.format("%.1f", height));

                obj.setCenter(new com.edge.vision.core.template.model.Point(centerX, centerY));
                obj.setWidth(width);
                obj.setHeight(height);
            }

            result.add(obj);
        }
        return result;
    }

    /**
     * 将 InspectionResult 转换为 QualityEvaluationResult
     * 用于 croparea 匹配模式
     */
    private QualityStandardService.QualityEvaluationResult convertToEvaluationResult(
            com.edge.vision.core.quality.InspectionResult inspectionResult) {

        QualityStandardService.QualityEvaluationResult result =
                new QualityStandardService.QualityEvaluationResult();

        result.setPartType(inspectionResult.getTemplateId());
        result.setPassed(inspectionResult.isPassed());
        result.setMessage(inspectionResult.getMessage());
        result.setProcessingTimeMs(inspectionResult.getProcessingTimeMs());
        result.setTemplateComparisons(new ArrayList<>());

        for (com.edge.vision.core.quality.FeatureComparison comp : inspectionResult.getComparisons()) {
            QualityStandardService.QualityEvaluationResult.TemplateComparison tc =
                    new QualityStandardService.QualityEvaluationResult.TemplateComparison();

            tc.setFeatureId(comp.getFeatureId());
            tc.setFeatureName(comp.getFeatureName());
            tc.setClassName(comp.getClassName());
            tc.setClassId(comp.getClassId());
            tc.setTemplatePosition(comp.getTemplatePosition());
            tc.setDetectedPosition(comp.getDetectedPosition());
            tc.setXError(comp.getXError());
            tc.setYError(comp.getYError());
            tc.setTotalError(comp.getTotalError());
            tc.setToleranceX(comp.getToleranceX());
            tc.setToleranceY(comp.getToleranceY());
            tc.setWithinTolerance(comp.isWithinTolerance());
            tc.setStatus(comp.getStatus());

            result.getTemplateComparisons().add(tc);
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
     * 获取跨平台中文字体
     */
    private Font getChineseFont(float size) {
        String[] fontNames = {
            "Microsoft YaHei",      // Windows
            "PingFang SC",          // macOS
            "WenQuanYi Micro Hei",  // Linux
            "SimSun",               // Windows 备选
            "Arial Unicode MS"      // 通用备选
        };

        for (String name : fontNames) {
            Font font = new Font(name, Font.PLAIN, (int) size);
            // 如果字体不是默认的 Dialog，说明找到了指定字体
            if (!font.getFamily().equalsIgnoreCase("Dialog")) {
                logger.debug("使用字体: {} (实际: {})", name, font.getFamily());
                return font;
            }
        }
        logger.debug("使用默认字体");
        return new Font(Font.SANS_SERIF, Font.PLAIN, (int) size);
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
        g2d.setFont(getChineseFont(14));

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

        // BufferedImage -> Mat 转换
        // 注意：如果原始Mat是灰度图，返回的是新的BGR Mat，不能直接写回原Mat
        byte[] data = ((DataBufferByte) bufferedImage.getRaster().getDataBuffer()).getData();
        Mat resultMat;
        if (image.channels() == 1) {
            // 原图是灰度图，创建新的BGR Mat
            resultMat = new Mat(image.rows(), image.cols(), CvType.CV_8UC3);
            resultMat.put(0, 0, data);
            image.release(); // 释放原始灰度Mat
        } else {
            // 原图是彩色图，直接写回
            image.put(0, 0, data);
            resultMat = image;
        }

        return resultMat;
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
        logger.info("matToBufferedImage - channels: {}, type: {}, depth: {}, size: {}x{}",
            mat.channels(), mat.type(), mat.depth(), mat.cols(), mat.rows());

        // 如果是灰度图（Mono8），先转换为BGR彩色图
        if (mat.channels() == 1) {
            Mat bgrMat = new Mat();
            Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_GRAY2BGR);

            BufferedImage image = new BufferedImage(bgrMat.cols(), bgrMat.rows(), BufferedImage.TYPE_3BYTE_BGR);
            byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            bgrMat.get(0, 0, data);

            bgrMat.release();
            return image;
        }

        // 彩色图（3通道或4通道）
        int type = BufferedImage.TYPE_3BYTE_BGR;
        if (mat.channels() == 4) {
            type = BufferedImage.TYPE_4BYTE_ABGR;
        }

        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), type);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat.get(0, 0, data);
        return image;
    }

    /**
     * 等比例压缩图片（如果任一边超过 6000）
     * 
     * @param src 原始 Mat
     * @return 压缩后的 Mat（如果不需要压缩则返回原 Mat）
     */
    private Mat resizeIfNeeded(Mat src) {
        int width = src.cols();
        int height = src.rows();
        int maxDim = Math.max(width, height);
        
        // 如果最大边不超过 6000，直接返回原图
        if (maxDim <= 6000) {
            return src;
        }
        
        // 计算等比例缩放比例
        double scale = 6000.0 / maxDim;
        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);
        
        logger.info("Resizing image from {}x{} to {}x{} (scale: {})", 
                width, height, newWidth, newHeight, String.format("%.4f", scale));
        
        // 执行 resize
        Mat dst = new Mat();
        Imgproc.resize(src, dst, new Size(newWidth, newHeight), 0, 0, Imgproc.INTER_AREA);
        
        return dst;
    }

    /**
     * 保存 Mat 到文件（使用 Java IO，支持中文路径）
     * 避免 OpenCV Imgcodecs.imwrite 在 Windows 中文路径上的问题
     * 
     * @param mat 要保存的图像
     * @param path 目标路径
     * @throws IOException 保存失败时抛出
     */
    private void saveMatToFile(Mat mat, Path path) throws IOException {
        // 将 Mat 编码为 JPEG 字节数组
        MatOfByte mob = new MatOfByte();
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95);
        boolean encoded = Imgcodecs.imencode(".jpg", mat, mob, params);
        
        if (!encoded) {
            mob.release();
            params.release();
            throw new IOException("Failed to encode image to JPEG");
        }
        
        byte[] imageBytes = mob.toArray();
        mob.release();
        params.release();
        
        // 使用 Java NIO 写入文件（支持中文路径）
        Files.write(path, imageBytes);
        logger.debug("Saved image to: {}", path.toAbsolutePath());
    }

    /**
     * 规范化路径字符串，处理中文字符和特殊字符
     * 
     * @param path 原始路径
     * @return 规范化后的路径
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        
        // 1. 去除首尾空白
        path = path.trim();
        
        // 2. 统一路径分隔符为系统默认分隔符
        path = path.replace('/', java.io.File.separatorChar)
                   .replace('\\', java.io.File.separatorChar);
        
        // 3. 处理 Windows 平台特殊字符
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            // Windows 文件名不能包含: < > : " | ? *
            path = path.replaceAll("[<>:\"|?*]", "_");
        }
        
        // 4. 移除控制字符（保留中文字符和其他 Unicode 字符）
        StringBuilder sb = new StringBuilder();
        for (char c : path.toCharArray()) {
            // 保留可打印字符、中文字符和其他 Unicode 字符
            if (c >= 0x20 || Character.isWhitespace(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        
        String result = sb.toString();
        logger.debug("Normalized path from '{}' to '{}'", path, result);
        return result;
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
