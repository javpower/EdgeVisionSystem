package com.edge.vision.controller;

import com.edge.vision.core.template.*;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.dto.InspectionRequest;
import com.edge.vision.dto.InspectionResponse;
import com.edge.vision.dto.TemplateBuildRequest;
import com.edge.vision.dto.TemplateBuildResponse;
import com.edge.vision.service.QualityStandardService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模板管理 API
 * <p>
 * 提供实时建模功能，支持图片和标注上传后立即生成模板并生效
 */
@Tag(name = "模板管理", description = "质量检测模板的构建、管理和应用")
@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private static final Logger logger = LoggerFactory.getLogger(TemplateController.class);

    @Autowired
    private TemplateManager templateManager;

    @Autowired
    private TemplateBuilder templateBuilder;

    @Autowired
    private QualityStandardService qualityStandardService;

    /**
     * 实时构建模板
     * <p>
     * 上传图片和 YOLO 标注，自动生成模板并立即生效
     */
    @Operation(summary = "实时构建模板", description = "上传图片和YOLO标注，自动生成模板并立即生效")
    @PostMapping("/build")
    public ResponseEntity<TemplateBuildResponse> buildTemplate(@RequestBody TemplateBuildRequest request) {
        try {
            logger.info("Building template: {}", request.getTemplateId());

            // 1. 处理 YOLO 标注（可能是文件路径或直接内容）
            String labelPath = request.getYoloLabels();
            if (labelPath == null || labelPath.isEmpty()) {
                // 使用模板标注目录
                labelPath = Paths.get("templates", "labels", request.getTemplateId() + ".txt").toString();
            }

            // 2. 解析类别映射（从前端传来的 JSON）
            Map<Integer, String> classNameMapping = new HashMap<>();
            if (request.getClassNameMapping() != null && !request.getClassNameMapping().isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    TypeReference<Map<String, String>> typeRef =
                        new TypeReference<Map<String, String>>() {};
                    Map<String, String> rawMapping = mapper.readValue(request.getClassNameMapping(), typeRef);
                    for (Map.Entry<String, String> entry : rawMapping.entrySet()) {
                        classNameMapping.put(Integer.parseInt(entry.getKey()), entry.getValue());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse classNameMapping: {}", e.getMessage());
                }
            }

            TemplateBuilder.BuildConfig config = TemplateBuilder.BuildConfig.builder()
                .templateId(request.getTemplateId())
                .tolerance(request.getToleranceX(), request.getToleranceY())
                .includeAuxiliaryAnchors(request.isIncludeAuxiliaryAnchors())
                .classNameMapping(classNameMapping);

            // 3. 构建模板
            String imagePath = request.getImageUrl();
            if (imagePath == null || imagePath.isEmpty()) {
                imagePath = Paths.get("templates", "images", request.getTemplateId() + ".jpg").toString();
            }

            Template template = templateBuilder.build(imagePath, labelPath, config);

            // 4. 保存模板
            templateManager.save(template);

            // 5. 立即生效（设置为当前模板）
            templateManager.setCurrentTemplate(template);

            // 6. 如果关联了工件类型，更新质检配置
            if (request.getPartType() != null && !request.getPartType().isEmpty()) {
                // 在模板元数据中记录关联的工件类型
                template.putMetadata("partType", request.getPartType());
                templateManager.save(template); // 重新保存以更新元数据
            }

            logger.info("Template built and activated: {}", template.getTemplateId());

            return ResponseEntity.ok(TemplateBuildResponse.success(request.getTemplateId(), template));

        } catch (IOException e) {
            logger.error("Failed to build template: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(TemplateBuildResponse.error("模板构建失败: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error building template", e);
            return ResponseEntity.internalServerError()
                .body(TemplateBuildResponse.error("内部错误: " + e.getMessage()));
        }
    }

    /**
     * 通过原始 YOLO 标注内容构建模板
     */
    @Operation(summary = "通过标注内容构建模板", description = "直接提供YOLO标注内容，自动生成模板")
    @PostMapping("/build-from-labels")
    public ResponseEntity<TemplateBuildResponse> buildFromLabels(@RequestBody TemplateBuildRequest request) {
        try {
            logger.info("Building template from inline labels: {}", request.getTemplateId());

            // 1. 将标注内容写入模板标注目录
            Path templateLabelDir = Paths.get("templates/labels");
            Files.createDirectories(templateLabelDir);

            Path labelPath = templateLabelDir.resolve(request.getTemplateId() + ".txt");
            Files.writeString(labelPath, request.getYoloLabels());

            // 2. 解析类别映射（从前端传来的 JSON）
            Map<Integer, String> classNameMapping = new HashMap<>();
            if (request.getClassNameMapping() != null && !request.getClassNameMapping().isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    TypeReference<Map<String, String>> typeRef =
                        new TypeReference<Map<String, String>>() {};
                    Map<String, String> rawMapping = mapper.readValue(request.getClassNameMapping(), typeRef);
                    for (Map.Entry<String, String> entry : rawMapping.entrySet()) {
                        classNameMapping.put(Integer.parseInt(entry.getKey()), entry.getValue());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse classNameMapping: {}", e.getMessage());
                }
            }

            TemplateBuilder.BuildConfig config = TemplateBuilder.BuildConfig.builder()
                .templateId(request.getTemplateId())
                .tolerance(request.getToleranceX(), request.getToleranceY())
                .includeAuxiliaryAnchors(request.isIncludeAuxiliaryAnchors())
                .classNameMapping(classNameMapping);

            String imagePath = request.getImageUrl();
            if (imagePath == null || imagePath.isEmpty()) {
                imagePath = Paths.get("templates", "images", request.getTemplateId() + ".jpg").toString();
            }

            Template template = templateBuilder.build(imagePath, labelPath.toString(), config);

            // 3. 保存并激活
            templateManager.save(template);
            templateManager.setCurrentTemplate(template);

            if (request.getPartType() != null && !request.getPartType().isEmpty()) {
                template.putMetadata("partType", request.getPartType());
                templateManager.save(template);
            }

            logger.info("Template built and activated: {}", template.getTemplateId());

            return ResponseEntity.ok(TemplateBuildResponse.success(request.getTemplateId(), template));

        } catch (Exception e) {
            logger.error("Failed to build template from labels", e);
            return ResponseEntity.internalServerError()
                .body(TemplateBuildResponse.error("模板构建失败: " + e.getMessage()));
        }
    }

    /**
     * 通过上传文件构建模板
     */
    @Operation(summary = "通过上传文件构建模板", description = "上传图片和YOLO标注文件，自动生成模板")
    @PostMapping(value = "/build-with-files", consumes = "multipart/form-data")
    public ResponseEntity<TemplateBuildResponse> buildWithFiles(
            @RequestParam("templateId") String templateId,
            @RequestParam(value = "partType", required = false) String partType,
            @RequestParam("imageFile") org.springframework.web.multipart.MultipartFile imageFile,
            @RequestParam("labelFile") org.springframework.web.multipart.MultipartFile labelFile,
            @RequestParam(value = "classNameMapping", required = false) String classNameMappingJson,
            @RequestParam(value = "toleranceX", defaultValue = "5.0") double toleranceX,
            @RequestParam(value = "toleranceY", defaultValue = "5.0") double toleranceY,
            @RequestParam(value = "includeAuxiliaryAnchors", defaultValue = "true") boolean includeAuxiliaryAnchors) {
        try {
            logger.info("Building template from files: {}", templateId);

            // 1. 保存上传的文件到 templates 目录
            Path templateImageDir = Paths.get("templates", "images").toAbsolutePath();
            Path templateLabelDir = Paths.get("templates", "labels").toAbsolutePath();
            Files.createDirectories(templateImageDir);
            Files.createDirectories(templateLabelDir);

            // 保存图片
            String imageExtension = getImageExtension(imageFile.getOriginalFilename());
            Path imagePath = templateImageDir.resolve(templateId + imageExtension);
            Files.copy(imageFile.getInputStream(), imagePath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 保存标注文件
            Path labelPath = templateLabelDir.resolve(templateId + ".txt");
            Files.copy(labelFile.getInputStream(), labelPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 2. 解析类别映射（从前端传来的 JSON）
            Map<Integer, String> classNameMapping = new HashMap<>();
            if (classNameMappingJson != null && !classNameMappingJson.isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    TypeReference<Map<String, String>> typeRef =
                        new TypeReference<Map<String, String>>() {};
                    Map<String, String> rawMapping = mapper.readValue(classNameMappingJson, typeRef);
                    for (Map.Entry<String, String> entry : rawMapping.entrySet()) {
                        classNameMapping.put(Integer.parseInt(entry.getKey()), entry.getValue());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse classNameMapping: {}", e.getMessage());
                }
            }

            TemplateBuilder.BuildConfig config = TemplateBuilder.BuildConfig.builder()
                .templateId(templateId)
                .tolerance(toleranceX, toleranceY)
                .includeAuxiliaryAnchors(includeAuxiliaryAnchors)
                .classNameMapping(classNameMapping);

            Template template = templateBuilder.build(imagePath.toString(), labelPath.toString(), config);

            // 3. 保存并激活
            templateManager.save(template);
            templateManager.setCurrentTemplate(template);

            if (partType != null && !partType.isEmpty()) {
                template.putMetadata("partType", partType);
                templateManager.save(template);
            }

            logger.info("Template built and activated: {}", template.getTemplateId());

            return ResponseEntity.ok(TemplateBuildResponse.success(templateId, template));

        } catch (Exception e) {
            logger.error("Failed to build template from files", e);
            return ResponseEntity.internalServerError()
                .body(TemplateBuildResponse.error("模板构建失败: " + e.getMessage()));
        }
    }

    private String getImageExtension(String filename) {
        if (filename == null) return ".jpg";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".bmp")) return ".bmp";
        return ".jpg"; // 默认
    }

    /**
     * 激活指定模板
     */
    @Operation(summary = "激活模板", description = "将指定模板设置为当前使用的模板")
    @PostMapping("/{templateId}/activate")
    public ResponseEntity<TemplateBuildResponse> activateTemplate(@PathVariable String templateId) {
        try {
            templateManager.setCurrentTemplate(templateId);

            Template template = templateManager.getCurrentTemplate();
            return ResponseEntity.ok(TemplateBuildResponse.success(templateId, template));

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                .body(TemplateBuildResponse.error("模板加载失败: " + e.getMessage()));
        }
    }

    /**
     * 获取当前激活的模板
     */
    @Operation(summary = "获取当前模板", description = "获取当前激活使用的模板信息")
    @GetMapping("/current")
    public ResponseEntity<Template> getCurrentTemplate() {
        Template template = templateManager.getCurrentTemplate();
        if (template == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(template);
    }

    /**
     * 获取所有可用模板
     */
    @Operation(summary = "获取所有模板", description = "获取所有已加载的模板列表")
    @GetMapping
    public ResponseEntity<List<Template>> getAllTemplates() {
        return ResponseEntity.ok(new ArrayList<>(templateManager.getAllTemplates()));
    }

    /**
     * 获取指定模板详情
     */
    @Operation(summary = "获取模板详情", description = "获取指定模板的详细信息")
    @GetMapping("/{templateId}")
    public ResponseEntity<Template> getTemplate(@PathVariable String templateId) {
        try {
            Template template = templateManager.load(templateId);
            return ResponseEntity.ok(template);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除指定模板
     */
    @Operation(summary = "删除模板", description = "删除指定的模板")
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String templateId) {
        templateManager.removeTemplate(templateId);
        return ResponseEntity.ok().build();
    }

    /**
     * 执行质量检测（使用当前模板）
     */
    @Operation(summary = "执行质量检测", description = "基于当前激活的模板执行质量检测")
    @PostMapping("/inspect")
    public ResponseEntity<InspectionResponse> inspect(@RequestBody InspectionRequest request) {
        try {
            // 转换 DTO 到内部模型
            List<DetectedObject> detectedObjects = request.getDetections().stream()
                .map(dto -> {
                    DetectedObject obj = new DetectedObject();
                    obj.setClassId(dto.getClassId());
                    obj.setClassName(dto.getClassName());
                    obj.setCenter(new Point(dto.getCenterX(), dto.getCenterY()));
                    obj.setWidth(dto.getWidth());
                    obj.setHeight(dto.getHeight());
                    obj.setConfidence(dto.getConfidence());
                    return obj;
                })
                .collect(Collectors.toList());

            // 执行检测（使用集成了新比对逻辑的服务）
            QualityStandardService.QualityEvaluationResult result =
                qualityStandardService.evaluateWithTemplate(request.getPartType(), detectedObjects);

            // 转换结果
            List<InspectionResponse.FeatureComparison> comparisons = null;
            if (result.getTemplateComparisons() != null) {
                // 创建 classId 到 className 的映射
                Map<Integer, String> classIdToName = detectedObjects.stream()
                    .filter(obj -> obj.getClassName() != null)
                    .collect(Collectors.toMap(
                        DetectedObject::getClassId,
                        DetectedObject::getClassName,
                        (existing, replacement) -> existing
                    ));

                comparisons = result.getTemplateComparisons().stream()
                    .map(comp -> {
                        InspectionResponse.FeatureComparison fc = new InspectionResponse.FeatureComparison();
                        fc.setFeatureId(comp.getFeatureId());
                        fc.setFeatureName(comp.getFeatureName());
                        fc.setClassName(classIdToName.getOrDefault(comp.getClassId(), "class_" + comp.getClassId()));
                        fc.setClassId(comp.getClassId());
                        fc.setTemplateX(comp.getTemplatePosition() != null ? comp.getTemplatePosition().x : 0);
                        fc.setTemplateY(comp.getTemplatePosition() != null ? comp.getTemplatePosition().y : 0);
                        fc.setDetectedX(comp.getDetectedPosition() != null ? comp.getDetectedPosition().x : 0);
                        fc.setDetectedY(comp.getDetectedPosition() != null ? comp.getDetectedPosition().y : 0);
                        fc.setXError(comp.getXError());
                        fc.setYError(comp.getYError());
                        fc.setTotalError(comp.getTotalError());
                        fc.setToleranceX(comp.getToleranceX());
                        fc.setToleranceY(comp.getToleranceY());
                        fc.setWithinTolerance(comp.isWithinTolerance());
                        fc.setStatus(comp.getStatus().toString());
                        return fc;
                    })
                    .collect(Collectors.toList());
            }

            return ResponseEntity.ok(InspectionResponse.success(
                result.isPassed(),
                result.getMessage(),
                result.getPartType(),
                comparisons,
                result.getProcessingTimeMs() != null ? result.getProcessingTimeMs() : 0
            ));

        } catch (Exception e) {
            logger.error("Inspection failed", e);
            return ResponseEntity.internalServerError()
                .body(InspectionResponse.success(false, "检测失败: " + e.getMessage(),
                    request.getPartType(), null, 0));
        }
    }

    /**
     * 重新加载所有模板
     */
    @Operation(summary = "重新加载模板", description = "从存储目录重新加载所有模板")
    @PostMapping("/reload")
    public ResponseEntity<Void> reloadTemplates() {
        templateManager.clearCache();
        templateManager.loadAllTemplates();
        return ResponseEntity.ok().build();
    }
}
