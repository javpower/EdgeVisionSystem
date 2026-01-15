package com.edge.vision.core.template;

import com.edge.vision.core.template.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板构建工具
 * <p>
 * 从图片和 YOLO 标注自动构建质量检测模板
 */
@Component
public class TemplateBuilder {
    private static final Logger logger = LoggerFactory.getLogger(TemplateBuilder.class);

    private final ObjectMapper objectMapper;
    private BuildConfig config;

    public TemplateBuilder() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.config = new BuildConfig();
    }

    public TemplateBuilder(BuildConfig config) {
        this();
        this.config = config;
    }

    /**
     * 构建模板
     *
     * @param imagePath     图片路径
     * @param yoloLabelPath YOLO 标注文件路径
     * @return 构建的模板
     */
    public Template build(String imagePath, String yoloLabelPath) throws IOException {
        return build(imagePath, yoloLabelPath, config);
    }

    /**
     * 从检测结果直接构建模板
     * <p>
     * 使用模型识别的结果直接创建模板，无需手动标注
     *
     * @param detectedObjects 模型检测到的对象列表
     * @param imageSize       图像尺寸
     * @param config          构建配置
     * @return 构建的模板
     */
    public Template buildFromDetection(List<DetectedObject> detectedObjects, ImageSize imageSize, BuildConfig config) {
        logger.info("Building template from {} detected objects", detectedObjects.size());

        if (detectedObjects.isEmpty()) {
            throw new IllegalArgumentException("No detected objects provided");
        }

        // 1. 计算边界框
        BoundingBox bbox = calculateBoundingBox(detectedObjects);
        logger.info("Calculated bounding box: {}", bbox);

        // 2. 生成锚点
        List<AnchorPoint> anchors = generateAnchorPoints(bbox);
        logger.info("Generated {} anchor points", anchors.size());

        // 3. 构建模板
        Template template = new Template(config.getTemplateId());
        template.setDescription("Auto-generated from model detection");
        template.setImageSize(imageSize);
        template.setBoundingBox(bbox);
        template.setAnchorPoints(anchors);
        template.setToleranceX(config.getDefaultToleranceX());
        template.setToleranceY(config.getDefaultToleranceY());

        // 4. 添加特征
        Point geometricCenter = bbox.getCenter();
        for (int i = 0; i < detectedObjects.size(); i++) {
            DetectedObject obj = detectedObjects.get(i);

            TemplateFeature feature = new TemplateFeature(
                "F" + i,
                obj.getClassName() != null ? obj.getClassName() : config.getClassName(obj.getClassId()),
                obj.getCenter(),
                obj.getClassId()
            );

            // 计算相对坐标
            Point relativePos = new Point(
                obj.getCenter().x - geometricCenter.x,
                obj.getCenter().y - geometricCenter.y
            );
            feature.setRelativePosition(relativePos);

            // 设置容差
            feature.setTolerance(new TemplateFeature.Tolerance(
                config.getDefaultToleranceX(),
                config.getDefaultToleranceY()
            ));

            template.addFeature(feature);
        }

        // 5. 添加元数据
        template.putMetadata("totalFeatures", detectedObjects.size());
        template.putMetadata("anchorCount", anchors.size());
        template.putMetadata("source", "model_detection");

        logger.info("Template built successfully from detection: {}", template.getTemplateId());
        return template;
    }

    /**
     * 从检测结果直接构建模板（使用默认配置）
     */
    public Template buildFromDetection(List<DetectedObject> detectedObjects, ImageSize imageSize) {
        return buildFromDetection(detectedObjects, imageSize, config);
    }

    /**
     * 构建模板
     *
     * @param imagePath     图片路径
     * @param yoloLabelPath YOLO 标注文件路径
     * @param config        构建配置
     * @return 构建的模板
     */
    public Template build(String imagePath, String yoloLabelPath, BuildConfig config) throws IOException {
        logger.info("Building template from: {}", imagePath);

        // 1. 获取图片尺寸
        ImageSize imageSize = getImageSize(imagePath);

        // 2. 解析 YOLO 标注
        List<DetectedObject> objects = YoloLabelParser.parse(
            yoloLabelPath,
            imageSize.getWidth(),
            imageSize.getHeight()
        );

        if (objects.isEmpty()) {
            throw new IllegalArgumentException("No valid objects found in label file: " + yoloLabelPath);
        }

        // 3. 计算边界框
        BoundingBox bbox = calculateBoundingBox(objects);
        logger.info("Calculated bounding box: {}", bbox);

        // 4. 生成锚点
        List<AnchorPoint> anchors = generateAnchorPoints(bbox);
        logger.info("Generated {} anchor points", anchors.size());

        // 5. 构建模板
        Template template = new Template(config.getTemplateId());
        template.setDescription("Auto-generated from " + imagePath);
        template.setImageSize(imageSize);
        template.setImagePath(imagePath);
        template.setBoundingBox(bbox);
        template.setAnchorPoints(anchors);
        template.setToleranceX(config.getDefaultToleranceX());
        template.setToleranceY(config.getDefaultToleranceY());

        // 6. 添加特征
        Point geometricCenter = bbox.getCenter();
        for (int i = 0; i < objects.size(); i++) {
            DetectedObject obj = objects.get(i);

            TemplateFeature feature = new TemplateFeature(
                "F" + i,
                config.getClassName(obj.getClassId()),
                obj.getCenter(),
                obj.getClassId()
            );

            // 计算相对坐标
            Point relativePos = new Point(
                obj.getCenter().x - geometricCenter.x,
                obj.getCenter().y - geometricCenter.y
            );
            feature.setRelativePosition(relativePos);

            // 设置容差
            feature.setTolerance(new TemplateFeature.Tolerance(
                config.getDefaultToleranceX(),
                config.getDefaultToleranceY()
            ));

            template.addFeature(feature);
        }

        // 7. 添加元数据
        template.putMetadata("totalFeatures", objects.size());
        template.putMetadata("anchorCount", anchors.size());
        template.putMetadata("sourceLabelFile", yoloLabelPath);

        logger.info("Template built successfully: {}", template.getTemplateId());
        return template;
    }

    /**
     * 计算边界框
     */
    private BoundingBox calculateBoundingBox(List<DetectedObject> objects) {
        double minX = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = Double.MIN_VALUE;

        for (DetectedObject obj : objects) {
            Point center = obj.getCenter();
            double halfW = obj.getWidth() / 2;
            double halfH = obj.getHeight() / 2;

            minX = Math.min(minX, center.x - halfW);
            maxX = Math.max(maxX, center.x + halfW);
            minY = Math.min(minY, center.y - halfH);
            maxY = Math.max(maxY, center.y + halfH);
        }

        // 添加边界填充
        double padding = config.getBoundingBoxPadding();
        minX -= padding;
        maxX += padding;
        minY -= padding;
        maxY += padding;

        return new BoundingBox(minX, maxX, minY, maxY);
    }

    /**
     * 自动生成十字锚点
     */
    private List<AnchorPoint> generateAnchorPoints(BoundingBox bbox) {
        List<AnchorPoint> anchors = new ArrayList<>();
        Point center = bbox.getCenter();

        // A0: 几何中心（主锚点）
        anchors.add(new AnchorPoint(
            "A0",
            AnchorPoint.AnchorType.GEOMETRIC_CENTER,
            center,
            "几何中心（主锚点）"
        ));

        if (config.isIncludeAuxiliaryAnchors()) {
            // A1: 上边界中心
            anchors.add(new AnchorPoint(
                "A1",
                AnchorPoint.AnchorType.TOP_CENTER,
                bbox.getTopCenter(),
                "上边界中心"
            ));

            // A2: 右边界中心
            anchors.add(new AnchorPoint(
                "A2",
                AnchorPoint.AnchorType.RIGHT_CENTER,
                bbox.getRightCenter(),
                "右边界中心"
            ));

            // A3: 下边界中心
            anchors.add(new AnchorPoint(
                "A3",
                AnchorPoint.AnchorType.BOTTOM_CENTER,
                bbox.getBottomCenter(),
                "下边界中心"
            ));

            // A4: 左边界中心
            anchors.add(new AnchorPoint(
                "A4",
                AnchorPoint.AnchorType.LEFT_CENTER,
                bbox.getLeftCenter(),
                "左边界中心"
            ));
        }

        return anchors;
    }

    /**
     * 获取图片尺寸
     */
    private ImageSize getImageSize(String imagePath) throws IOException {
        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            logger.warn("Image file not found: {}, using default size", imagePath);
            return new ImageSize(1920, 1080);
        }

        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            logger.warn("Failed to read image: {}, using default size", imagePath);
            return new ImageSize(1920, 1080);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        logger.info("Read image size: {}x{} from {}", width, height, imagePath);
        return new ImageSize(width, height);
    }

    /**
     * 保存模板到文件
     */
    public void saveTemplate(Template template, String outputPath) throws IOException {
        Path path = Path.of(outputPath);
        Files.createDirectories(path.getParent());

        objectMapper.writeValue(path.toFile(), template);
        logger.info("Template saved to: {}", outputPath);
    }

    /**
     * 从文件加载模板
     */
    public Template loadTemplate(String templatePath) throws IOException {
        return objectMapper.readValue(new File(templatePath), Template.class);
    }

    /**
     * 构建配置
     */
    public static class BuildConfig {
        private String templateId = "template_" + System.currentTimeMillis();
        private double defaultToleranceX = 5.0;
        private double defaultToleranceY = 5.0;
        private double boundingBoxPadding = 10.0;
        private boolean includeAuxiliaryAnchors = true;
        private Map<Integer, String> classNameMapping = Map.of(
            0, "特征0",
            1, "特征1",
            2, "特征2",
            3, "特征3",
            4, "特征4"
        );

        public static BuildConfig builder() {
            return new BuildConfig();
        }

        public BuildConfig templateId(String templateId) {
            this.templateId = templateId;
            return this;
        }

        public BuildConfig tolerance(double x, double y) {
            this.defaultToleranceX = x;
            this.defaultToleranceY = y;
            return this;
        }

        public BuildConfig boundingBoxPadding(double padding) {
            this.boundingBoxPadding = padding;
            return this;
        }

        public BuildConfig includeAuxiliaryAnchors(boolean include) {
            this.includeAuxiliaryAnchors = include;
            return this;
        }

        public BuildConfig classNameMapping(Map<Integer, String> mapping) {
            this.classNameMapping = mapping;
            return this;
        }

        // Getters
        public String getTemplateId() { return templateId; }
        public double getDefaultToleranceX() { return defaultToleranceX; }
        public double getDefaultToleranceY() { return defaultToleranceY; }
        public double getBoundingBoxPadding() { return boundingBoxPadding; }
        public boolean isIncludeAuxiliaryAnchors() { return includeAuxiliaryAnchors; }
        public String getClassName(int classId) {
            return classNameMapping.getOrDefault(classId, "特征" + classId);
        }
        public Map<Integer, String> getClassNameMapping() { return classNameMapping; }
    }
}
