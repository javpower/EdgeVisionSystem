package com.edge.vision.service;

import ai.onnxruntime.OrtException;
import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.model.Detection;
import com.edge.vision.util.VisionTool;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工件-摄像头-模板映射管理服务
 */
@Service
public class PartCameraTemplateService {
    private static final Logger logger = LoggerFactory.getLogger(PartCameraTemplateService.class);

    @Autowired
    private TemplateManager templateManager;

    @Autowired
    private InferenceEngineService inferenceEngineService;

    @Autowired
    private CameraService cameraService;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    // 存储工件-摄像头-模板的映射关系
    // Key: partType, Value: Map<cameraId, templateId>
    private final Map<String, Map<Integer, String>> partCameraTemplateMap = new ConcurrentHashMap<>();

    /**
     * 获取工件的所有摄像头模板映射
     * 优先从内存map获取，如果为空则从模板元数据重建
     */
    public Map<Integer, String> getCameraTemplates(String partType) {
        Map<Integer, String> cameraMap = partCameraTemplateMap.get(partType);
        if (cameraMap != null && !cameraMap.isEmpty()) {
            return cameraMap;
        }

        // 从模板元数据重建映射
        Map<Integer, String> rebuiltMap = rebuildCameraTemplateMap(partType);
        if (rebuiltMap != null && !rebuiltMap.isEmpty()) {
            // 缓存到内存map
            partCameraTemplateMap.put(partType, rebuiltMap);
            logger.info("Rebuilt camera template map for partType {}: {}", partType, rebuiltMap);
            return rebuiltMap;
        }

        return new HashMap<>();
    }

    /**
     * 获取工件指定摄像头的模板
     * 优先从内存map获取，如果为空则从模板元数据查找
     */
    public Template getCameraTemplate(String partType, int cameraId) {
        // 先尝试从内存map获取
        Map<Integer, String> cameraMap = partCameraTemplateMap.get(partType);
        if (cameraMap != null) {
            String templateId = cameraMap.get(cameraId);
            if (templateId != null) {
                try {
                    return templateManager.load(templateId);
                } catch (IOException e) {
                    logger.error("Failed to load template: {}", templateId, e);
                    return null;
                }
            }
        }

        // 从模板元数据查找
        return findTemplateByPartTypeAndCamera(partType, cameraId);
    }

    /**
     * 从模板文件重建工件-摄像头-模板映射
     */
    private Map<Integer, String> rebuildCameraTemplateMap(String partType) {
        Map<Integer, String> cameraMap = new HashMap<>();
        List<Template> templates = templateManager.getTemplatesByPartType(partType);

        for (Template template : templates) {
            if (template.getMetadata() != null && template.getMetadata().containsKey("cameraId")) {
                Object cameraIdObj = template.getMetadata().get("cameraId");
                Integer cameraId = null;
                if (cameraIdObj instanceof Integer) {
                    cameraId = (Integer) cameraIdObj;
                } else if (cameraIdObj instanceof String) {
                    try {
                        cameraId = Integer.parseInt((String) cameraIdObj);
                    } catch (NumberFormatException e) {
                        // 忽略无法解析的cameraId
                    }
                }

                if (cameraId != null) {
                    cameraMap.put(cameraId, template.getTemplateId());
                }
            }
        }

        logger.debug("Rebuilt camera template map for partType {}: {} mappings from {} templates",
            partType, cameraMap.size(), templates.size());

        return cameraMap;
    }

    /**
     * 根据工件类型和摄像头ID查找模板
     */
    private Template findTemplateByPartTypeAndCamera(String partType, int cameraId) {
        List<Template> templates = templateManager.getTemplatesByPartType(partType);

        for (Template template : templates) {
            if (template.getMetadata() != null && template.getMetadata().containsKey("cameraId")) {
                Object cameraIdObj = template.getMetadata().get("cameraId");
                int templateCameraId = -1;
                if (cameraIdObj instanceof Integer) {
                    templateCameraId = (Integer) cameraIdObj;
                } else if (cameraIdObj instanceof String) {
                    try {
                        templateCameraId = Integer.parseInt((String) cameraIdObj);
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                }

                if (templateCameraId == cameraId) {
                    return template;
                }
            }
        }

        logger.warn("Template not found for partType={}, cameraId={}", partType, cameraId);
        return null;
    }

    /**
     * 保存工件-摄像头-模板映射
     */
    public void saveCameraTemplate(String partType, int cameraId, String templateId) {
        partCameraTemplateMap.computeIfAbsent(partType, k -> new HashMap<>()).put(cameraId, templateId);
        logger.info("Saved mapping: {} -> camera {} -> template {}", partType, cameraId, templateId);
    }

    /**
     * 批量保存工件-摄像头-模板映射
     */
    public void saveCameraTemplates(String partType, Map<Integer, String> mappings) {
        partCameraTemplateMap.put(partType, new HashMap<>(mappings));
        logger.info("Saved batch mappings for {}: {}", partType, mappings);
    }

    /**
     * 删除工件的所有模板映射
     */
    public void removePartTemplates(String partType) {
        // 先从内存移除
        Map<Integer, String> cameraMap = partCameraTemplateMap.remove(partType);

        // 删除模板文件
        List<Template> templates = templateManager.getTemplatesByPartType(partType);
        for (Template template : templates) {
            templateManager.removeTemplate(template.getTemplateId());
        }

        logger.info("Removed all templates for part: {} ({} templates)", partType, templates.size());
    }

    /**
     * 删除工件指定摄像头的模板
     */
    public void removeCameraTemplate(String partType, int cameraId) {
        // 从内存移除
        Map<Integer, String> cameraMap = partCameraTemplateMap.get(partType);
        if (cameraMap != null) {
            cameraMap.remove(cameraId);
        }

        // 删除模板文件
        Template template = findTemplateByPartTypeAndCamera(partType, cameraId);
        if (template != null) {
            templateManager.removeTemplate(template.getTemplateId());
            logger.info("Removed template: {} -> camera {} -> template {}", partType, cameraId, template.getTemplateId());
        }
    }

    /**
     * 获取已配置的工件类型列表
     */
    public Set<String> getConfiguredPartTypes() {
        return new HashSet<>(partCameraTemplateMap.keySet());
    }

    /**
     * 获取工件的摄像头数量
     * 优先从内存map获取，如果为空则从模板元数据统计
     */
    public int getCameraCount(String partType) {
        // 先尝试从内存map获取
        Map<Integer, String> cameraMap = partCameraTemplateMap.get(partType);
        if (cameraMap != null && !cameraMap.isEmpty()) {
            return cameraMap.size();
        }

        // 如果内存map为空，从TemplateManager统计
        return getCameraCountFromTemplates(partType);
    }

    /**
     * 从模板文件统计指定工件类型的摄像头数量
     */
    private int getCameraCountFromTemplates(String partType) {
        Set<Integer> cameraIds = new HashSet<>();
        List<Template> templates = templateManager.getTemplatesByPartType(partType);

        for (Template template : templates) {
            if (template.getMetadata() != null && template.getMetadata().containsKey("cameraId")) {
                Object cameraIdObj = template.getMetadata().get("cameraId");
                if (cameraIdObj instanceof Integer) {
                    cameraIds.add((Integer) cameraIdObj);
                } else if (cameraIdObj instanceof String) {
                    try {
                        cameraIds.add(Integer.parseInt((String) cameraIdObj));
                    } catch (NumberFormatException e) {
                        // 忽略无法解析的cameraId
                    }
                }
            }
        }

        logger.debug("getCameraCountFromTemplates for partType {}: {} cameras from {} templates",
            partType, cameraIds.size(), templates.size());

        return cameraIds.size();
    }

    /**
     * 多摄像头质检
     * 返回每个摄像头各自的质检结果
     *
     * 流程：
     * 1. 获取所有摄像头的图像和模板
     * 2. 一次性 predictBatch 获取所有检测结果
     * 3. 根据模板类型分别处理：
     *    - 空模板：检测到任何特征 = 不合格
     *    - 正常模板：模板匹配 + 检测结果比对
     */
    public List<CameraInspectionResult> inspect(String partType) throws OrtException, IOException {
        long startTime = System.currentTimeMillis();
        logger.info("=== Starting multi-camera inspection for part: {} ===", partType);

        Map<Integer, String> cameraTemplates = getCameraTemplates(partType);

        if (cameraTemplates.isEmpty()) {
            throw new IllegalStateException("No templates found for part: " + partType);
        }

        List<CameraInspectionResult> results = new ArrayList<>();
        List<CameraImageData> cameraDataList = new ArrayList<>();

        // 步骤1：获取所有摄像头的图像和模板
        long loadStartTime = System.currentTimeMillis();
        for (Map.Entry<Integer, String> entry : cameraTemplates.entrySet()) {
            int cameraId = entry.getKey();
            String templateId = entry.getValue();

            Mat imageMat = getCameraImageMat(cameraId);
            if (imageMat == null || imageMat.empty()) {
                logger.warn("Failed to get image for camera {}", cameraId);
                continue;
            }

            Template template = templateManager.load(templateId);
            if (template == null) {
                logger.warn("Failed to load template {}", templateId);
                imageMat.release();
                continue;
            }

            cameraDataList.add(new CameraImageData(cameraId, imageMat, template));
        }
        logger.info("Step 1 - Loaded {} camera images and templates in {} ms",
            cameraDataList.size(), System.currentTimeMillis() - loadStartTime);

        if (cameraDataList.isEmpty()) {
            return results;
        }

        try {
            // 步骤2：一次性 predictBatch 获取所有检测结果
            List<Mat> allImageMats = new ArrayList<>();
            for (CameraImageData data : cameraDataList) {
                allImageMats.add(data.imageMat);
            }

            long predictStartTime = System.currentTimeMillis();
            List<List<com.edge.vision.model.Detection>> allDetections =
                inferenceEngineService.getDetailInferenceEngine().predictBatch(allImageMats);
            long predictTime = System.currentTimeMillis() - predictStartTime;
            logger.info("Step 2 - predictBatch completed for {} cameras in {} ms (avg: {} ms/camera)",
                allImageMats.size(), predictTime, predictTime / allImageMats.size());

            // 步骤3：根据每个摄像头模板类型分别处理
            for (int i = 0; i < cameraDataList.size(); i++) {
                long cameraStartTime = System.currentTimeMillis();
                CameraImageData data = cameraDataList.get(i);
                Mat imageMat = data.imageMat;
                Template template = data.template;
                List<com.edge.vision.model.Detection> detections = allDetections.get(i);

                if (detections == null) {
                    detections = new ArrayList<>();
                }
                logger.debug("Camera {}: {} YOLO detections", data.cameraId, detections.size());

                // 检查是否为空模板
                boolean isEmpty = template.getFeatures() == null || template.getFeatures().isEmpty() ||
                    (template.getMetadata() != null && Boolean.TRUE.equals(template.getMetadata().get("emptyTemplate")));
                logger.debug("Camera {}: isEmpty={}, featuresInTemplate={}",
                    data.cameraId, isEmpty, template.getFeatures() != null ? template.getFeatures().size() : 0);

                List<FeatureComparisonInfo> features = new ArrayList<>();
                boolean passed = false;
                Mat resultMat = null;

                if (isEmpty) {
                    // 空模板：检测到任何特征 = 不合格
                    passed = detections.isEmpty();

                    // 转换检测结果为 FeatureInfo
                    for (int j = 0; j < detections.size(); j++) {
                        com.edge.vision.model.Detection det = detections.get(j);
                        float[] bbox = det.getBbox();
                        if (bbox != null && bbox.length >= 4) {
                            FeatureComparisonInfo info = new FeatureComparisonInfo();
                            info.featureId = "extra_" + j;
                            info.featureName = det.getLabel();
                            info.className = det.getLabel();
                            info.classId = det.getClassId();
                            info.detectedX = (bbox[0] + bbox[2]) / 2.0;
                            info.detectedY = (bbox[1] + bbox[3]) / 2.0;
                            info.status = "EXTRA";  // 多余特征
                            features.add(info);
                        }
                    }

                    // 绘制检测框
                    resultMat = drawDetections(imageMat.clone(), detections);

                    logger.info("Camera {} (empty template): passed={}, detections={}, time={} ms",
                        data.cameraId, passed, detections.size(), System.currentTimeMillis() - cameraStartTime);

                } else {
                    // 正常模板：模板匹配 + 检测结果比对
                    long calculateStartTime = System.currentTimeMillis();
                    List<DetectedObject> templateObjects = VisionTool.calculateTemplateCoordinates(template, imageMat);
                    long calculateTime = System.currentTimeMillis() - calculateStartTime;
                    logger.info("Camera {}: calculateTemplateCoordinates took {} ms, matched {} objects",
                        data.cameraId, calculateTime, templateObjects != null ? templateObjects.size() : 0);

                    // 校验1：检查是否匹配到任何特征
                    if (templateObjects == null || templateObjects.isEmpty()) {
                        // 工件摆错了，请调整工件方向或工件类型错误
                        String errorMsg = "请调整工件方向";
                        logger.warn("Camera {}: Template matching failed - {} (time: {} ms)",
                            data.cameraId, errorMsg, System.currentTimeMillis() - cameraStartTime);

                        // 转换结果图片（带检测框）
                        resultMat = drawDetections(imageMat.clone(), detections);
                        String resultImageBase64 = matToBase64(resultMat);
                        resultMat.release();
                        resultMat = null;

                        results.add(new CameraInspectionResult(
                            data.cameraId, template.getTemplateId(), false,
                            "data:image/jpeg;base64," + resultImageBase64, errorMsg, new ArrayList<>()
                        ));
                        continue;
                    }

                    // 打印每个匹配对象的 insideBounds 状态
                    for (int objIdx = 0; objIdx < templateObjects.size(); objIdx++) {
                        DetectedObject obj = templateObjects.get(objIdx);
                        logger.debug("Camera {}: templateObject[{}] insideBounds={}",
                            data.cameraId, objIdx, obj.isInsideBounds());
                    }

                    // 校验2：检查是否有特征超出边界（工件没对齐）
                    boolean hasOutOfBounds = false;
                    for (DetectedObject obj : templateObjects) {
                        if (!obj.isInsideBounds()) {
                            hasOutOfBounds = true;
                            break;
                        }
                    }

                    if (hasOutOfBounds) {
                        // 工件没对齐，请对齐到正确位置
                        String errorMsg = "请对齐到正确位置";
                        logger.warn("Camera {}: Template has out-of-bounds features - {} (time: {} ms)",
                            data.cameraId, errorMsg, System.currentTimeMillis() - cameraStartTime);

                        // 转换结果图片
                        resultMat = drawDetections(imageMat.clone(), detections);
                        String resultImageBase64 = matToBase64(resultMat);
                        resultMat.release();
                        resultMat = null;

                        results.add(new CameraInspectionResult(
                            data.cameraId, template.getTemplateId(), false,
                            "data:image/jpeg;base64," + resultImageBase64, errorMsg, new ArrayList<>()
                        ));
                        continue;
                    }

                    // 校验3：模板匹配结果通过，继续质检
                    if (!validateTemplateObjects(templateObjects, template)) {
                        logger.warn("Camera {}: Template validation failed (time: {} ms)",
                            data.cameraId, System.currentTimeMillis() - cameraStartTime);
                        results.add(new CameraInspectionResult(
                            data.cameraId, template.getTemplateId(), false, null, new ArrayList<>()
                        ));
                        continue;
                    }

                    List<DetectedObject> detectedObjects = convertDetectionsToDetectedObjects(detections);
                    logger.debug("Camera {}: converted {} YOLO detections to DetectedObject",
                        data.cameraId, detectedObjects.size());

                    // 比对结果
                    long compareStartTime = System.currentTimeMillis();
                    List<com.edge.vision.service.QualityStandardService.QualityEvaluationResult.TemplateComparison> comparisons =
                        VisionTool.compareResults(
                            templateObjects,
                            detectedObjects,
                            template.getToleranceX(),
                            template.getToleranceY()
                        );
                    long compareTime = System.currentTimeMillis() - compareStartTime;
                    logger.info("Camera {}: compareResults took {} ms, {} comparisons",
                        data.cameraId, compareTime, comparisons.size());

                    // 转换为 FeatureInfo
                    for (var comp : comparisons) {
                        FeatureComparisonInfo info = new FeatureComparisonInfo();
                        info.featureId = comp.getFeatureId();
                        info.featureName = comp.getFeatureName();
                        info.className = comp.getClassName();
                        info.classId = comp.getClassId();
                        info.templateX = comp.getTemplatePosition() != null ? comp.getTemplatePosition().x : 0;
                        info.templateY = comp.getTemplatePosition() != null ? comp.getTemplatePosition().y : 0;
                        info.detectedX = comp.getDetectedPosition() != null ? comp.getDetectedPosition().x : 0;
                        info.detectedY = comp.getDetectedPosition() != null ? comp.getDetectedPosition().y : 0;
                        info.xError = comp.getXError();
                        info.yError = comp.getYError();
                        info.totalError = comp.getTotalError();
                        info.toleranceX = comp.getToleranceX();
                        info.toleranceY = comp.getToleranceY();
                        info.withinTolerance = comp.isWithinTolerance();
                        info.status = comp.getStatus().toString();
                        features.add(info);
                    }

                    passed = comparisons.stream()
                        .allMatch(c -> c.getStatus() == com.edge.vision.core.quality.FeatureComparison.ComparisonStatus.PASSED);

                    // 绘制带框图片（YOLO检测框 + 模板比对结果）
                    resultMat = drawDetectionsWithTemplate(imageMat.clone(), detections, comparisons);

                    logger.info("Camera {} (normal template): passed={}, features={}, time={} ms",
                        data.cameraId, passed, features.size(), System.currentTimeMillis() - cameraStartTime);
                }

                // 转换结果图片为 base64
                if (resultMat != null) {
                    long encodeStartTime = System.currentTimeMillis();
                    String resultImageBase64 = matToBase64(resultMat);
                    logger.debug("Camera {}: image encoding took {} ms", data.cameraId, System.currentTimeMillis() - encodeStartTime);
                    resultMat.release();

                    results.add(new CameraInspectionResult(
                        data.cameraId,
                        template.getTemplateId(),
                        passed,
                        "data:image/jpeg;base64," + resultImageBase64,
                        features,detections
                    ));
                } else {
                    results.add(new CameraInspectionResult(
                        data.cameraId,
                        template.getTemplateId(),
                        false,
                        null,
                        features
                    ));
                }
            }
        } finally {
            // 释放所有 Mat
            for (CameraImageData data : cameraDataList) {
                if (data.imageMat != null) {
                    data.imageMat.release();
                }
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("=== Multi-camera inspection completed: partType={}, cameras={}, results={}, totalTime={} ms ===",
            partType, cameraDataList.size(), results.size(), totalTime);

        return results;
    }

    /**
     * 获取摄像头当前帧的 Mat（直接从 CameraService 获取）
     */
    private Mat getCameraImageMat(int cameraId) {
        if (cameraService != null) {
            return cameraService.getCameraImageMat(cameraId);
        }
        return null;
    }

    /**
     * 校验模板匹配结果
     * 可根据实际需求重写此方法
     *
     * @param templateObjects 模板匹配结果
     * @param template 模板对象
     * @return true-校验通过，false-校验失败
     */
    protected boolean validateTemplateObjects(List<DetectedObject> templateObjects, Template template) {
        // 默认校验：检查是否为空
        if (templateObjects == null || templateObjects.isEmpty()) {
            return false;
        }

        // 可添加更多校验逻辑，例如：
        // - 检查匹配的特征数量是否足够
        // - 检查匹配的置信度
        // - 检查特征位置的合理性

        return true;
    }

    /**
     * 获取摄像头当前帧的Base64
     */
    private String getCameraImageBase64(int cameraId) {
        if (cameraService != null) {
            return cameraService.getCameraImageBase64(cameraId);
        }
        return null;
    }

    /**
     * Base64转Mat
     */
    private Mat base64ToMat(String base64) {
        try {
            if (base64 == null) return null;
            if (base64.contains(",")) base64 = base64.split(",")[1];
            byte[] data = Base64.getDecoder().decode(base64);
            MatOfByte mob = new MatOfByte(data);
            Mat mat = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
            mob.release();
            return mat;
        } catch (Exception e) {
            logger.error("Failed to decode base64 image", e);
            return null;
        }
    }

    /**
     * 摄像头质检结果（内部使用）
     */
    public static class CameraInspectionResult {
        public int cameraId;
        public String templateId;
        public boolean passed;
        public String imageUrl;  // 带框的图片
        public String errorMessage;  // 错误信息
        public List<FeatureComparisonInfo> features;

        public java.util.List<Detection> details;


        public CameraInspectionResult(int cameraId, String templateId, boolean passed, String imageUrl, List<FeatureComparisonInfo> features) {
            this.cameraId = cameraId;
            this.templateId = templateId;
            this.passed = passed;
            this.imageUrl = imageUrl;
            this.features = features;
        }
        public CameraInspectionResult(int cameraId, String templateId, boolean passed, String imageUrl, List<FeatureComparisonInfo> features,List<Detection> details) {
            this.cameraId = cameraId;
            this.templateId = templateId;
            this.passed = passed;
            this.imageUrl = imageUrl;
            this.features = features;
            this.details=details;
        }

        public CameraInspectionResult(int cameraId, String templateId, boolean passed, String imageUrl, String errorMessage, List<FeatureComparisonInfo> features) {
            this.cameraId = cameraId;
            this.templateId = templateId;
            this.passed = passed;
            this.imageUrl = imageUrl;
            this.errorMessage = errorMessage;
            this.features = features;
        }
        public CameraInspectionResult(int cameraId, String templateId, boolean passed, String imageUrl, String errorMessage, List<FeatureComparisonInfo> features,List<Detection> details) {
            this.cameraId = cameraId;
            this.templateId = templateId;
            this.passed = passed;
            this.imageUrl = imageUrl;
            this.errorMessage = errorMessage;
            this.features = features;
            this.details=details;
        }
    }

    /**
     * 特征比对信息（内部使用）
     */
    public static class FeatureComparisonInfo {
        public String featureId;
        public String featureName;
        public String className;
        public int classId;
        public double templateX;
        public double templateY;
        public double detectedX;
        public double detectedY;
        public double xError;
        public double yError;
        public double totalError;
        public double toleranceX;
        public double toleranceY;
        public boolean withinTolerance;
        public String status;
    }

    /**
     * 批量预览YOLO识别结果
     */
    public List<CameraPreviewResult> previewDetections(String partType, List<CameraImageData> imageDataList) throws OrtException {
        long startTime = System.currentTimeMillis();
        logger.info("=== Starting preview detection for part: {} ===", partType);

        List<CameraPreviewResult> results = new ArrayList<>();

        // 过滤出有画面的摄像头
        List<CameraImageData> validImageData = new ArrayList<>();
        List<Integer> validIndices = new ArrayList<>();

        for (int i = 0; i < imageDataList.size(); i++) {
            CameraImageData imageData = imageDataList.get(i);
            // 检查是否有画面（cropRect 不为空）
            if (imageData.cropRect != null && imageData.cropRect.length >= 4) {
                validImageData.add(imageData);
                validIndices.add(i);
                logger.debug("Camera {}: cropRect=[{},{},{},{}]",
                    imageData.cameraId, imageData.cropRect[0], imageData.cropRect[1],
                    imageData.cropRect[2], imageData.cropRect[3]);
            } else {
                // 没有画面，返回空结果
                results.add(new CameraPreviewResult(imageData.cameraId, null, new ArrayList<>()));
                logger.info("Camera {} has no cropRect, skipping YOLO detection", imageData.cameraId);
            }
        }

        if (validImageData.isEmpty()) {
            logger.info("No valid images for YOLO preview");
            return results;
        }

        // 准备所有图像的Mat
        long loadStartTime = System.currentTimeMillis();
        List<Mat> imageMats = new ArrayList<>();
        for (CameraImageData imageData : validImageData) {
            Mat mat = Imgcodecs.imread(imageData.tempImagePath);
            if (mat != null && !mat.empty()) {
                imageMats.add(mat);
                logger.debug("Loaded preview image for camera {}: {}x{}", imageData.cameraId, mat.cols(), mat.rows());
            } else {
                logger.warn("Failed to load preview image for camera {}", imageData.cameraId);
                imageMats.add(null);
            }
        }
        logger.info("Loaded {} preview images in {} ms", imageMats.size(), System.currentTimeMillis() - loadStartTime);

        try {
            if (inferenceEngineService.isDetailEngineAvailable()) {
                // 批量YOLO识别
                long predictStartTime = System.currentTimeMillis();
                List<List<com.edge.vision.model.Detection>> allDetections =
                    inferenceEngineService.getDetailInferenceEngine().predictBatch(imageMats);
                long predictTime = System.currentTimeMillis() - predictStartTime;
                logger.info("YOLO predictBatch for preview: {} images in {} ms (avg: {} ms/image)",
                    imageMats.size(), predictTime, predictTime / imageMats.size());

                // 处理每个摄像头的识别结果
                for (int idx = 0; idx < validImageData.size(); idx++) {
                    long cameraStartTime = System.currentTimeMillis();
                    CameraImageData imageData = validImageData.get(idx);
                    Mat imageMat = imageMats.get(idx);
                    List<com.edge.vision.model.Detection> detections = allDetections.get(idx);

                    if (imageMat == null || detections == null) {
                        results.add(new CameraPreviewResult(imageData.cameraId, null, new ArrayList<>()));
                        continue;
                    }

                    logger.info("Camera {}: {} YOLO detections for preview", imageData.cameraId, detections.size());

                    // 在图片上绘制检测框
                    long drawStartTime = System.currentTimeMillis();
                    Mat resultMat = drawDetections(imageMat.clone(), detections);
                    logger.debug("Camera {}: drawDetections took {} ms", imageData.cameraId, System.currentTimeMillis() - drawStartTime);

                    String resultImageBase64 = matToBase64(resultMat);
                    resultMat.release();

                    // 转换检测结果
                    List<FeatureInfo> features = new ArrayList<>();
                    for (int j = 0; j < detections.size(); j++) {
                        com.edge.vision.model.Detection det = detections.get(j);
                        float[] bbox = det.getBbox();
                        if (bbox != null && bbox.length >= 4) {
                            features.add(new FeatureInfo(
                                "feature_" + j,
                                det.getLabel(),
                                det.getClassId(),
                                (bbox[0] + bbox[2]) / 2.0,
                                (bbox[1] + bbox[3]) / 2.0,
                                bbox[2] - bbox[0],
                                bbox[3] - bbox[1],
                                det.getConfidence()
                            ));
                        }
                    }

                    results.add(new CameraPreviewResult(
                        imageData.cameraId,
                        "data:image/jpeg;base64," + resultImageBase64,
                        features
                    ));

                    logger.info("Camera {}: Preview complete with {} features (time: {} ms)",
                        imageData.cameraId, features.size(), System.currentTimeMillis() - cameraStartTime);
                }
            } else {
                throw new IllegalStateException("Detail inference engine not available");
            }
        } finally {
            // 释放所有Mat
            for (Mat mat : imageMats) {
                if (mat != null) {
                    mat.release();
                }
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("=== Preview detection completed: partType={}, results={}, totalTime={} ms ===",
            partType, results.size(), totalTime);

        return results;
    }

    /**
     * 批量创建模板（使用YOLO识别）
     * cropRect 为空的摄像头将创建空模板（无 features）
     */
    public List<Template> createTemplatesBatch(
            String partType,
            List<CameraImageData> imageDataList,
            double toleranceX,
            double toleranceY) throws OrtException, IOException {

        long startTime = System.currentTimeMillis();
        logger.info("=== Starting batch template creation for part: {} ===", partType);

        List<Template> templates = new ArrayList<>();

        // 分离有画面和没有画面的摄像头
        List<CameraImageData> validImageData = new ArrayList<>();
        List<CameraImageData> emptyImageData = new ArrayList<>();

        for (CameraImageData imageData : imageDataList) {
            if (imageData.cropRect != null && imageData.cropRect.length >= 4) {
                validImageData.add(imageData);
            } else {
                emptyImageData.add(imageData);
                logger.info("Camera {} has no cropRect, will create empty template", imageData.cameraId);
            }
        }

        // 先为没有画面的摄像头创建空模板
        for (CameraImageData imageData : emptyImageData) {
            String templateId = partType + "_camera_" + imageData.cameraId;

            // 创建空模板
            Template template = new Template();
            template.setTemplateId(templateId);
            template.setFeatures(new ArrayList<>());  // 空特征列表
            template.setPartType(partType);
            template.setToleranceX(toleranceX);
            template.setToleranceY(toleranceY);

            // 设置元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("cameraId", imageData.cameraId);
            metadata.put("partType", partType);
            metadata.put("matchStrategy", "MULTI_CAMERA");
            metadata.put("emptyTemplate", true);  // 标记为空模板
            template.setMetadata(metadata);

            // 保存模板
            templateManager.save(template);

            // 保存映射关系
            saveCameraTemplate(partType, imageData.cameraId, templateId);

            templates.add(template);

            logger.info("Created empty template {} for camera {}", templateId, imageData.cameraId);
        }

        // 如果没有需要识别的摄像头，直接返回
        if (validImageData.isEmpty()) {
            logger.info("No valid images for YOLO detection, returning {} templates", templates.size());
            return templates;
        }

        // 准备所有图像的Mat
        long loadStartTime = System.currentTimeMillis();
        List<Mat> imageMats = new ArrayList<>();
        for (CameraImageData imageData : validImageData) {
            Mat mat = Imgcodecs.imread(imageData.tempImagePath);
            if (mat != null && !mat.empty()) {
                imageMats.add(mat);
                logger.debug("Loaded image for camera {}: {}x{}", imageData.cameraId, mat.cols(), mat.rows());
            } else {
                logger.warn("Failed to load image for camera {}", imageData.cameraId);
                // 保持索引对齐，添加null占位
                imageMats.add(null);
            }
        }
        logger.info("Loaded {} images in {} ms", imageMats.size(), System.currentTimeMillis() - loadStartTime);

        try {
            // 批量YOLO识别
            if (inferenceEngineService.isDetailEngineAvailable()) {
                long predictStartTime = System.currentTimeMillis();
                List<List<com.edge.vision.model.Detection>> allDetections =
                    inferenceEngineService.getDetailInferenceEngine().predictBatch(imageMats);
                long predictTime = System.currentTimeMillis() - predictStartTime;
                logger.info("YOLO predictBatch completed for {} images in {} ms (avg: {} ms/image)",
                    imageMats.size(), predictTime, predictTime / imageMats.size());

                // 为每个有画面的摄像头创建模板
                for (int i = 0; i < validImageData.size(); i++) {
                    long cameraStartTime = System.currentTimeMillis();
                    CameraImageData imageData = validImageData.get(i);
                    List<com.edge.vision.model.Detection> detections = allDetections.get(i);

                    int detectionCount = detections != null ? detections.size() : 0;
                    logger.info("Camera {}: {} YOLO detections", imageData.cameraId, detectionCount);

                    if (detections == null || detections.isEmpty()) {
                        logger.warn("Camera {}: No detections, creating template with empty features", imageData.cameraId);
                    }

                    // 转换为DetectedObject列表
                    List<DetectedObject> detectedObjects = detections != null ?
                        convertDetectionsToDetectedObjects(detections) : new ArrayList<>();

                    // 生成模板ID：partType_cameraId
                    String templateId = partType + "_camera_" + imageData.cameraId;

                    // 读取图片为base64
                    String base64Image = matToBase64(imageMats.get(i));

                    // 创建模板
                    Template template = VisionTool.createTemplate(
                        base64Image,
                        imageData.cropRect,
                        detectedObjects,
                        templateId
                    );

                    // 设置元数据
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("cameraId", imageData.cameraId);
                    metadata.put("partType", partType);
                    metadata.put("matchStrategy", "MULTI_CAMERA");
                    // 检查是否为空模板
                    boolean isEmpty = detectedObjects.isEmpty();
                    metadata.put("emptyTemplate", isEmpty);
                    template.setMetadata(metadata);

                    // 设置容差
                    template.setToleranceX(toleranceX);
                    template.setToleranceY(toleranceY);
                    template.setPartType(partType);

                    // 保存模板
                    templateManager.save(template);

                    // 保存映射关系
                    saveCameraTemplate(partType, imageData.cameraId, templateId);

                    templates.add(template);

                    logger.info("Camera {}: Created template {} with {} features (time: {} ms)",
                        imageData.cameraId, templateId, detectedObjects.size(), System.currentTimeMillis() - cameraStartTime);
                }
            } else {
                throw new IllegalStateException("Detail inference engine not available");
            }
        } finally {
            // 释放所有Mat
            for (Mat mat : imageMats) {
                if (mat != null) {
                    mat.release();
                }
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("=== Batch template creation completed: partType={}, templates={}, totalTime={} ms ===",
            partType, templates.size(), totalTime);

        return templates;
    }

    /**
     * 摄像头图像数据（内部使用）
     */
    public static class CameraImageData {
        public int cameraId;
        public Mat imageMat;
        public Template template;

        // 用于预览和保存
        public int[] cropRect;
        public String tempImagePath;

        public CameraImageData(int cameraId, Mat imageMat, Template template) {
            this.cameraId = cameraId;
            this.imageMat = imageMat;
            this.template = template;
        }

        public CameraImageData(int cameraId, int[] cropRect, String tempImagePath) {
            this.cameraId = cameraId;
            this.cropRect = cropRect;
            this.tempImagePath = tempImagePath;
        }
    }

    /**
     * 摄像头预览结果（内部使用）
     */
    public static class CameraPreviewResult {
        public int cameraId;
        public String imageUrl;
        public List<FeatureInfo> features;

        public CameraPreviewResult(int cameraId, String imageUrl, List<FeatureInfo> features) {
            this.cameraId = cameraId;
            this.imageUrl = imageUrl;
            this.features = features;
        }
    }

    /**
     * 特征信息（内部使用）
     */
    public static class FeatureInfo {
        public String featureId;
        public String className;
        public int classId;
        public double centerX;
        public double centerY;
        public double width;
        public double height;
        public double confidence;

        public FeatureInfo(String featureId, String className, int classId,
                          double centerX, double centerY, double width, double height, double confidence) {
            this.featureId = featureId;
            this.className = className;
            this.classId = classId;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
        }
    }

    /**
     * 转换Detection到DetectedObject
     */
    private List<DetectedObject> convertDetectionsToDetectedObjects(List<com.edge.vision.model.Detection> detections) {
        List<DetectedObject> result = new ArrayList<>();
        for (com.edge.vision.model.Detection detection : detections) {
            DetectedObject obj = new DetectedObject();
            obj.setClassName(detection.getLabel());
            obj.setClassId(detection.getClassId());
            obj.setConfidence(detection.getConfidence());

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

    /**
     * 将Mat转换为Base64字符串
     */
    private String matToBase64(Mat mat) {
        MatOfByte mob = new MatOfByte();
        Imgcodecs.imencode(".jpg", mat, mob);
        byte[] bytes = mob.toArray();
        mob.release();
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 在图像上绘制检测框（YOLO检测）- 简单版本
     */
    private Mat drawDetections(Mat image, List<com.edge.vision.model.Detection> detections) {
        for (com.edge.vision.model.Detection detection : detections) {
            float[] bbox = detection.getBbox();
            if (bbox != null && bbox.length >= 4) {
                // 绘制边界框
                org.opencv.core.Point p1 = new org.opencv.core.Point(bbox[0], bbox[1]);
                org.opencv.core.Point p2 = new org.opencv.core.Point(bbox[2], bbox[3]);
                Imgproc.rectangle(image, p1, p2, new Scalar(0, 255, 0), 2);

                // 绘制标签
                String label = String.format("%s: %.2f", detection.getLabel(), detection.getConfidence());
                int[] baseline = new int[1];
                org.opencv.core.Size textSize = Imgproc.getTextSize(
                    label, Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, 1, baseline);

                double textY = Math.max(bbox[1] - 5, textSize.height + 5);
                org.opencv.core.Point textPos = new org.opencv.core.Point(bbox[0], textY);

                // 绘制背景
                org.opencv.core.Point bg1 = new org.opencv.core.Point(
                    bbox[0], textY - textSize.height - 5);
                org.opencv.core.Point bg2 = new org.opencv.core.Point(
                    bbox[0] + textSize.width, textY + 5);
                Imgproc.rectangle(image, bg1, bg2, new Scalar(0, 255, 0), -1);

                // 绘制文字
                Imgproc.putText(image, label, textPos,
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                    new Scalar(0, 0, 0), 1);
            }
        }
        return image;
    }

    /**
     * 在图像上绘制YOLO检测框和模板比对结果
     * 参考 InspectController 的绘制方式
     */
    private Mat drawDetectionsWithTemplate(Mat image, List<com.edge.vision.model.Detection> detections,
                                            List<com.edge.vision.service.QualityStandardService.QualityEvaluationResult.TemplateComparison> comparisons) {
        // 使用 Graphics2D 绘制
        java.awt.image.BufferedImage bufferedImage = matToBufferedImage(image);
        java.awt.Graphics2D g2d = bufferedImage.createGraphics();
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 1. 绘制所有YOLO检测框（绿色框）
        for (com.edge.vision.model.Detection detection : detections) {
            float[] bbox = detection.getBbox();
            if (bbox != null && bbox.length >= 4) {
                int x1 = (int) bbox[0];
                int y1 = (int) bbox[1];
                int x2 = (int) bbox[2];
                int y2 = (int) bbox[3];

                g2d.setColor(java.awt.Color.GREEN);
                g2d.drawRect(x1, y1, x2 - x1, y2 - y1);

                // 绘制标签
                String label = String.format("%s: %.2f", detection.getLabel(), detection.getConfidence());
                g2d.drawString(label, x1, Math.max(y1 - 5, 15));
            }
        }

        // 2. 根据比对状态绘制不同的标注
        if (comparisons != null) {
            for (com.edge.vision.service.QualityStandardService.QualityEvaluationResult.TemplateComparison comp : comparisons) {
                switch (comp.getStatus()) {
                    case MISSING -> drawMissingAnnotation(g2d, comp);
                    case EXTRA -> drawExtraAnnotation(g2d, comp);
                    case PASSED -> drawPassedAnnotation(g2d, comp);
                    case DEVIATION_EXCEEDED -> drawDeviationAnnotation(g2d, comp);
                }
            }
        }

        g2d.dispose();

        // BufferedImage -> Mat 转换
        byte[] data = ((java.awt.image.DataBufferByte) bufferedImage.getRaster().getDataBuffer()).getData();
        Mat resultMat;
        if (image.channels() == 1) {
            resultMat = new Mat(image.rows(), image.cols(), CvType.CV_8UC3);
            resultMat.put(0, 0, data);
        } else {
            image.put(0, 0, data);
            resultMat = image;
        }

        return resultMat;
    }

    /**
     * 绘制合格标注（绿色小方块）
     */
    private void drawPassedAnnotation(java.awt.Graphics2D g2d,
                                      com.edge.vision.service.QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;
        int size = 8;

        g2d.setColor(java.awt.Color.GREEN);
        g2d.fillRect((int) x - size, (int) y - size, size * 2, size * 2);
    }

    /**
     * 绘制偏差标注（黄色空心框）
     */
    private void drawDeviationAnnotation(java.awt.Graphics2D g2d,
                                          com.edge.vision.service.QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;
        int size = 10;

        g2d.setColor(java.awt.Color.YELLOW);
        g2d.setStroke(new java.awt.BasicStroke(2));
        g2d.drawRect((int) x - size, (int) y - size, size * 2, size * 2);
    }

    /**
     * 绘制错检标注（红色X + 外圈）
     */
    private void drawExtraAnnotation(java.awt.Graphics2D g2d,
                                      com.edge.vision.service.QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getDetectedPosition() == null) return;

        double x = comp.getDetectedPosition().x;
        double y = comp.getDetectedPosition().y;
        int size = 25;

        g2d.setColor(java.awt.Color.RED);
        g2d.setStroke(new java.awt.BasicStroke(3));

        // 绘制红色X
        g2d.drawLine((int) x - size, (int) y - size, (int) x + size, (int) y + size);
        g2d.drawLine((int) x + size, (int) y - size, (int) x - size, (int) y + size);

        // 绘制外圈
        g2d.setStroke(new java.awt.BasicStroke(2));
        g2d.drawOval((int) x - size - 5, (int) y - size - 5, (size + 5) * 2, (size + 5) * 2);
    }

    /**
     * 绘制漏检标注（红色虚线框 + 中心十字）
     */
    private void drawMissingAnnotation(java.awt.Graphics2D g2d,
                                        com.edge.vision.service.QualityStandardService.QualityEvaluationResult.TemplateComparison comp) {
        if (comp.getTemplatePosition() == null) return;

        double x = comp.getTemplatePosition().x;
        double y = comp.getTemplatePosition().y;
        int size = 30;

        g2d.setColor(java.awt.Color.RED);

        // 绘制虚线框
        int x1 = (int) (x - size), y1 = (int) (y - size);
        int x2 = (int) (x + size), y2 = (int) (y + size);
        g2d.setStroke(new java.awt.BasicStroke(2, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
                1.0f, new float[]{10, 5}, 0));
        g2d.drawRect(x1, y1, x2 - x1, y2 - y1);

        // 绘制中心十字
        g2d.setStroke(new java.awt.BasicStroke(2));
        g2d.drawLine((int) x - 10, (int) y, (int) x + 10, (int) y);
        g2d.drawLine((int) x, (int) y - 10, (int) x, (int) y + 10);

        // 绘制文字
        g2d.setStroke(new java.awt.BasicStroke(1));
        g2d.drawString("漏检: " + comp.getFeatureName(), x1, y1 - 10);
    }

    /**
     * Mat 转换为 BufferedImage
     */
    private java.awt.image.BufferedImage matToBufferedImage(Mat mat) {
        // 如果是灰度图，先转换为BGR彩色图
        if (mat.channels() == 1) {
            Mat bgrMat = new Mat();
            Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_GRAY2BGR);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                bgrMat.cols(), bgrMat.rows(), java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
            byte[] data = ((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData();
            bgrMat.get(0, 0, data);
            bgrMat.release();
            return image;
        }

        // 彩色图（3通道或4通道）
        int type = java.awt.image.BufferedImage.TYPE_3BYTE_BGR;
        if (mat.channels() == 4) {
            type = java.awt.image.BufferedImage.TYPE_4BYTE_ABGR;
        }

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(mat.cols(), mat.rows(), type);
        byte[] data = ((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat.get(0, 0, data);
        return image;
    }
}
