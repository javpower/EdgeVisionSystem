package com.edge.vision.core.template;

import com.edge.vision.core.template.model.Template;
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

    public TemplateManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.templateCache = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        if (autoLoad) {
            loadAllTemplates();
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
     * @param template 模板对象
     */
    public void save(Template template) throws IOException {
        // 检查是否存在相同 partType 的旧模板
        String partType = template.getPartType();
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
                            if (partType.equals(existingTemplate.getPartType())) {
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
        logger.info("Template cache cleared");
    }

    /**
     * 设置模板目录
     */
    public void setTemplateDirectory(String directory) {
        this.templateDirectory = directory;
    }
}
