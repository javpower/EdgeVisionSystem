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

    private static final String IMAGE_SUBDIR = "images";    // 模板标准图片子目录
    private static final String LABEL_SUBDIR = "labels";    // 模板标注子目录

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
    public void save(Template template) throws IOException {
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
        logger.info("Saved template: {} to {}", template.getTemplateId(), path);
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
     * 从缓存移除模板
     */
    public void removeTemplate(String templateId) {
        templateCache.remove(templateId);
        if (currentTemplate != null &&
            currentTemplate.getTemplateId().equals(templateId)) {
            currentTemplate = null;
        }
        logger.info("Removed template from cache: {}", templateId);
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
