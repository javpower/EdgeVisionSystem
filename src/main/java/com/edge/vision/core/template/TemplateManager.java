package com.edge.vision.core.template;

import com.edge.vision.core.template.model.Template;
import com.edge.vision.util.VisionTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板管理器
 * <p>
 * 负责模板的加载、缓存、保存和切换
 */
@Component
public class TemplateManager {
    private static final Logger logger = LoggerFactory.getLogger(TemplateManager.class);

    @Value("${edge-vision.template.directory:templates}")
    private String templateDirectory;

    @Value("${edge-vision.template.auto-load:true}")
    private boolean autoLoad;

    private final ObjectMapper objectMapper;
    private final Map<String, Template> templateCache;
    private Template currentTemplate;
    private String currentPartType;  // 当前工件类型（用于多摄像头模板）

    public TemplateManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.templateCache = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        if (autoLoad) {
            loadAllTemplates();
            templateCache.values().forEach(v-> VisionTool.getOrComputeTemplateFeatures(v));
        }
    }

    /**
     * 加载指定模板
     *
     * @param templateId 模板ID
     * @return 模板对象
     */
    public Template load(String templateId) throws IOException {
        // 先从缓存查找
        Template cached = templateCache.get(templateId);
        if (cached != null) {
            return cached;
        }

        // 从文件加载
        Path path = Paths.get(templateDirectory, templateId + ".json");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Template not found: " + path);
        }

        Template template = objectMapper.readValue(path.toFile(), Template.class);
        templateCache.put(templateId, template);
        logger.info("Loaded template: {} from {}", templateId, path);
        return template;
    }

    /**
     * 从文件路径加载模板
     *
     * @param templatePath 模板文件路径
     * @return 模板对象
     */
    public Template loadFromFile(String templatePath) throws IOException {
        File file = new File(templatePath);
        Template template = objectMapper.readValue(file, Template.class);

        // 添加到缓存
        templateCache.put(template.getTemplateId(), template);
        logger.info("Loaded template from file: {}", templatePath);
        return template;
    }

    /**
     * 保存模板
     *
     * @param template 模板对象
     */
    /**
     * 保存模板（自动处理同工件类型的旧模板）
     * 如果存在相同 partType 的旧模板，先删除再保存新的
     *
     * 注意：多摄像头模板（metadata中有cameraId的）不会删除同类型的其他模板
     *
     * @param template 模板对象
     */
    public void save(Template template) throws IOException {
        // 获取工件类型
        String partType = template.getPartType();

        // 检查是否为多摄像头模板（metadata中有cameraId）
        boolean isMultiCameraTemplate = template.getMetadata() != null
            && template.getMetadata().containsKey("cameraId");

        // 只对非多摄像头模板执行删除同类型旧模板的逻辑
        if (!isMultiCameraTemplate) {
            // 检查是否存在相同 partType 的旧模板
            if (partType != null && !partType.isEmpty()) {
                // 先检查缓存中的模板
                List<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, Template> entry : templateCache.entrySet()) {
                    String existingId = entry.getKey();
                    Template existingTemplate = entry.getValue();
                    if (existingTemplate != null && partType.equals(existingTemplate.getPartType())) {
                        // 找到相同 partType 的模板，但 ID 不同（是不同的模板）
                        if (!existingId.equals(template.getTemplateId())) {
                            toRemove.add(existingId);
                            logger.info("Found existing template in cache with same partType: {}, old template: {}, new template: {}",
                                partType, existingId, template.getTemplateId());
                        }
                    }
                }

                // 也检查目录中的模板文件（处理未加载到缓存的模板）
                Path dirPath = Paths.get(templateDirectory);
                if (Files.exists(dirPath)) {
                    File[] files = dirPath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
                    if (files != null) {
                        for (File file : files) {
                            String fileName = file.getName();
                            String existingId = fileName.substring(0, fileName.length() - 5); // 去掉 .json
                            // 跳过新模板本身和已标记删除的
                            if (existingId.equals(template.getTemplateId()) || toRemove.contains(existingId)) {
                                continue;
                            }

                            // 读取模板检查 partType
                            try {
                                Template existingTemplate = objectMapper.readValue(file, Template.class);
                                // 只删除非多摄像头模板
                                boolean isExistingMultiCamera = existingTemplate.getMetadata() != null
                                    && existingTemplate.getMetadata().containsKey("cameraId");
                                if (!isExistingMultiCamera && partType.equals(existingTemplate.getPartType())) {
                                    toRemove.add(existingId);
                                    logger.info("Found existing template in directory with same partType: {}, old template: {}",
                                        partType, existingId);
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to read template file: {}", file, e);
                            }
                        }
                    }
                }

                // 删除旧的同类型模板
                for (String oldTemplateId : toRemove) {
                    removeTemplate(oldTemplateId);
                }
            }
        }

        // 确保目录存在
        Path dirPath = Paths.get(templateDirectory);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }

        // 保存到文件
        Path path = dirPath.resolve(template.getTemplateId() + ".json");
        objectMapper.writeValue(path.toFile(), template);

        // 更新缓存
        templateCache.put(template.getTemplateId(), template);
        logger.info("Saved template: {} (partType: {}) to {}", template.getTemplateId(), partType, path);

        // 如果是多摄像头模板，自动设置当前工件类型
        if (isMultiCameraTemplate && partType != null && !partType.isEmpty()) {
            setCurrentPartType(partType);
        }
    }

    /**
     * 加载目录中的所有模板
     */
    public void loadAllTemplates() {
        Path dirPath = Paths.get(templateDirectory);
        if (!Files.exists(dirPath)) {
            logger.warn("Template directory does not exist: {}", templateDirectory);
            return;
        }

        try {
            File[] files = dirPath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null || files.length == 0) {
                logger.info("No template files found in {}", templateDirectory);
                return;
            }

            for (File file : files) {
                try {
                    Template template = objectMapper.readValue(file, Template.class);
                    templateCache.put(template.getTemplateId(), template);
                    logger.debug("Loaded template: {}", template.getTemplateId());
                } catch (IOException e) {
                    logger.error("Failed to load template from {}", file, e);
                }
            }

            logger.info("Loaded {} templates from {}", templateCache.size(), templateDirectory);

            // 如果有模板但当前模板为空，自动设置第一个为当前模板
            if (!templateCache.isEmpty() && currentTemplate == null) {
                Template firstTemplate = templateCache.values().iterator().next();
                setCurrentTemplate(firstTemplate);
                logger.info("Auto-activated template: {}", firstTemplate.getTemplateId());
            }
        } catch (Exception e) {
            logger.error("Error loading templates", e);
        }
    }

    /**
     * 获取当前激活的模板
     */
    public Template getCurrentTemplate() {
        return currentTemplate;
    }

    /**
     * 设置当前激活的模板
     */
    public void setCurrentTemplate(Template template) {
        this.currentTemplate = template;
        logger.info("Current template set to: {}", template.getTemplateId());
    }

    /**
     * 设置当前激活的模板（通过ID）
     */
    public void setCurrentTemplate(String templateId) throws IOException {
        Template template = load(templateId);
        setCurrentTemplate(template);
    }

    /**
     * 获取所有已加载的模板ID
     */
    public Set<String> getAvailableTemplateIds() {
        return new HashSet<>(templateCache.keySet());
    }

    /**
     * 获取所有已加载的模板
     */
    public Collection<Template> getAllTemplates() {
        return new ArrayList<>(templateCache.values());
    }

    /**
     * 检查模板是否存在
     */
    public boolean hasTemplate(String templateId) {
        return templateCache.containsKey(templateId);
    }

    /**
     * 从缓存移除模板并删除文件
     */
    public void removeTemplate(String templateId) {
        // 先从缓存移除
        templateCache.remove(templateId);

        // 如果删除的是当前激活的模板，清空
        if (currentTemplate != null &&
            currentTemplate.getTemplateId().equals(templateId)) {
            currentTemplate = null;
        }

        // 删除磁盘上的模板文件
        try {
            Path path = Paths.get(templateDirectory, templateId + ".json");
            if (Files.exists(path)) {
                Files.delete(path);
                logger.info("Deleted template file: {}", path);
            } else {
                logger.warn("Template file not found: {}", path);
            }
        } catch (IOException e) {
            logger.error("Failed to delete template file for: {}", templateId, e);
        }

        logger.info("Removed template: {}", templateId);
    }

    /**
     * 清空模板缓存
     */
    public void clearCache() {
        templateCache.clear();
        currentTemplate = null;
        currentPartType = null;
        logger.info("Template cache cleared");
    }

    // ============================================================
    // 多摄像头模板管理（新增）
    // ============================================================

    /**
     * 设置当前工件类型（用于多摄像头建模）
     * 同时会清除单摄像头模板的 currentTemplate
     *
     * @param partType 工件类型
     */
    public void setCurrentPartType(String partType) {
        this.currentPartType = partType;
        this.currentTemplate = null;  // 清除单摄像头模板
        logger.info("Current partType set to: {}", partType);
    }

    /**
     * 获取当前工件类型
     *
     * @return 当前工件类型，可能为 null
     */
    public String getCurrentPartType() {
        return currentPartType;
    }

    /**
     * 获取当前工件类型的所有模板（包括单摄像头和多摄像头）
     * 优先返回 currentPartType 对应的所有模板，如果没有则返回 currentTemplate
     *
     * @return 模板列表
     */
    public List<Template> getCurrentTemplates() {
        List<Template> templates = new ArrayList<>();

        // 如果有当前工件类型，获取该类型的所有模板
        if (currentPartType != null && !currentPartType.isEmpty()) {
            for (Template template : templateCache.values()) {
                if (currentPartType.equals(template.getPartType())) {
                    templates.add(template);
                }
            }
            logger.debug("Found {} templates for current partType: {}", templates.size(), currentPartType);
        }
        // 如果有单摄像头当前模板，返回它
        else if (currentTemplate != null) {
            templates.add(currentTemplate);
            logger.debug("Returning single current template: {}", currentTemplate.getTemplateId());
        }

        return templates;
    }

    /**
     * 检查是否存在指定工件类型的模板
     *
     * @param partType 工件类型
     * @return 是否存在
     */
    public boolean hasTemplatesForPartType(String partType) {
        for (Template template : templateCache.values()) {
            if (partType.equals(template.getPartType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取指定工件类型的所有模板
     *
     * @param partType 工件类型
     * @return 模板列表
     */
    public List<Template> getTemplatesByPartType(String partType) {
        List<Template> templates = new ArrayList<>();
        for (Template template : templateCache.values()) {
            if (partType.equals(template.getPartType())) {
                templates.add(template);
            }
        }
        return templates;
    }

    /**
     * 获取所有工件类型
     *
     * @return 工件类型列表
     */
    public Set<String> getAllPartTypes() {
        Set<String> partTypes = new HashSet<>();
        for (Template template : templateCache.values()) {
            String partType = template.getPartType();
            if (partType != null && !partType.isEmpty()) {
                partTypes.add(partType);
            }
        }
        return partTypes;
    }

    /**
     * 设置模板目录
     */
    public void setTemplateDirectory(String directory) {
        this.templateDirectory = directory;
    }
}
