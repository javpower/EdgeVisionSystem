package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.infer.YOLOInferenceEngine;
import com.edge.vision.model.*;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.DataManager;
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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/inspect")
public class InspectController {
    private static final Logger logger = LoggerFactory.getLogger(InspectController.class);

    @Autowired
    private YamlConfig config;

    @Autowired
    private CameraService cameraService;

    @Autowired
    private DataManager dataManager;

    // 类型识别引擎（可选）
    private YOLOInferenceEngine typeInferenceEngine;

    // 细节检测引擎（必须）
    private YOLOInferenceEngine detailInferenceEngine;

    // 临时存储预检数据
    private final Map<String, PreCheckData> preCheckStore = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // === 修改点 1: 初始化不再传递 classNames，模型会自动读取 ===
        // 初始化类型识别引擎（可选）
        if (config.getModels().getTypeModel() != null && !config.getModels().getTypeModel().isEmpty()) {
            try {
                typeInferenceEngine = new YOLOInferenceEngine(
                        config.getModels().getTypeModel(),
                        config.getModels().getConfThres(),
                        config.getModels().getIouThres(),
                        config.getModels().getDevice()
                        // 移除了 classNames 参数
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
                        config.getModels().getDevice()
                        // 移除了 classNames 参数
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

            // 类型识别逻辑
            if (typeInferenceEngine != null) {
                try {
                    Mat stitchedMat = base64ToMat(stitchedImageBase64);
                    List<Detection> typeDetections = typeInferenceEngine.predict(stitchedMat);

                    // === 修改点 2: 适配新的 Detection Getter 方法 ===
                    if (!typeDetections.isEmpty()) {
                        Detection bestDetection = typeDetections.stream()
                                .max(Comparator.comparing(Detection::getConfidence)) // getScore -> getConfidence
                                .orElse(null);

                        if (bestDetection != null && bestDetection.getConfidence() > 0.5) {
                            suggestedType = bestDetection.getLabel(); // getClassName -> getLabel
                            logger.info("Suggested type: {} (confidence: {})", suggestedType, bestDetection.getConfidence());
                        }
                    }
                    stitchedMat.release();
                } catch (Exception e) {
                    logger.warn("Type detection failed", e);
                }
            }

            PreCheckData preCheckData = new PreCheckData();
            preCheckData.setStitchedImage(stitchedImageBase64);
            preCheckData.setTimestamp(LocalDateTime.now());
            preCheckStore.put(requestId, preCheckData);

            PreCheckResponse preCheckResponse = new PreCheckResponse();
            preCheckResponse.setRequestId(requestId);
            preCheckResponse.setSuggestedType(suggestedType);
            preCheckResponse.setPreviewImage("data:image/jpeg;base64," + stitchedImageBase64);
            preCheckResponse.setCameraCount(cameraService.getCameraCount());
            // 注意：这里可能需要根据实际拼接后的尺寸动态获取
            preCheckResponse.setImageShape(new int[]{720, 1280, 3});

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
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody ConfirmRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // ... (校验代码保持不变) ...
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

            // === 修改点 3: 绘制逻辑适配 float[] xyxy ===
            Mat resultMat = drawDetections(stitchedMat.clone(), detailDetections);
            String resultImageBase64 = matToBase64(resultMat);

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
            analysis.setQualityStatus(detailDetections.isEmpty() ? "PASS" : "FAIL");
            data.setAnalysis(analysis);

            data.setDeviceId(config.getSystem().getDeviceId());
            data.setTimestamp(System.currentTimeMillis() / 1000);
            data.setResultImage("data:image/jpeg;base64," + resultImageBase64);

            // 保存记录 (逻辑保持不变)
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
            resultMat.release();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Confirm inspection failed", e);
            response.put("status", "error");
            response.put("message", "Inspection failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ... getRecords 和 getStats 保持不变 ...
    /**
     * 查询记录
     */
    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> getRecords(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String batchId,
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

    // === 修改点 4: 重写绘制方法，适配 float[] bbox 和 xyxy 坐标 ===
    private Mat drawDetections(Mat image, List<Detection> detections) {
        for (Detection detection : detections) {
            float[] bbox = detection.getBbox(); // 现在是 float[] [x1, y1, x2, y2]

            if (bbox != null && bbox.length >= 4) {
                // Point 需要 double 类型，直接传入 float 即可
                Point p1 = new Point(bbox[0], bbox[1]); // 左上角
                Point p2 = new Point(bbox[2], bbox[3]); // 右下角

                // 绘制矩形框
                Imgproc.rectangle(image, p1, p2, new Scalar(0, 255, 0), 2);

                // 绘制标签
                String label = String.format("%s: %.2f", detection.getLabel(), detection.getConfidence());

                // 确保文字不会画出图片上边界
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