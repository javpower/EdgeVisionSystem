package com.edge.vision.core.topology.croparea;

import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.TemplateFeature;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 裁剪区域模板构建器
 * <p>
 * 建模流程：
 * 1. 用整体模型检测工件，获取四个角坐标
 * 2. 根据角坐标裁剪工件区域
 * 3. 用细节模型在裁剪区域中识别特征（hole、nut 等）
 * 4. 保存特征相对坐标
 */
@Component
public class CropAreaTemplateBuilder {
    private static final Logger logger = LoggerFactory.getLogger(CropAreaTemplateBuilder.class);

    /**
     * 从特征列表构建模板
     *
     * @param templateId       模板ID
     * @param cropWidth        裁剪区域宽度
     * @param cropHeight       裁剪区域高度
     * @param features         特征列表（在裁剪区域中的相对坐标）
     * @return 裁剪区域模板
     */
    public CropAreaTemplate buildFromFeatures(
            String templateId,
            int cropWidth,
            int cropHeight,
            List<TemplateFeature> features) {

        logger.info("Building CropAreaTemplate: id={}, size={}x{}, features={}",
            templateId, cropWidth, cropHeight, features.size());

        CropAreaTemplate.Builder builder = new CropAreaTemplate.Builder()
            .templateId(templateId)
            .cropSize(cropWidth, cropHeight);

        for (TemplateFeature feature : features) {
            logger.debug("  Adding feature: id={}, class={}, pos=({},{})",
                feature.getId(), feature.getClassId(),
                feature.getPosition().x, feature.getPosition().y);

            // 验证特征坐标在裁剪区域内
            if (feature.getPosition().x < 0 || feature.getPosition().x >= cropWidth ||
                feature.getPosition().y < 0 || feature.getPosition().y >= cropHeight) {
                logger.warn("    Warning: feature position outside crop area, clamping");
            }

            builder.addFeature(feature);
        }

        CropAreaTemplate template = builder.build();
        logger.info("Built template: {}", template);

        return template;
    }

    /**
     * 从检测对象列表构建模板（简化版本）
     *
     * @param templateId       模板ID
     * @param cropWidth        裁剪区域宽度
     * @param cropHeight       裁剪区域高度
     * @param detectedObjects  检测到的对象列表
     * @param defaultTolerance 默认容差
     * @return 裁剪区域模板
     */
    public CropAreaTemplate buildFromDetectedObjects(
            String templateId,
            int cropWidth,
            int cropHeight,
            List<DetectedObject> detectedObjects,
            Point defaultTolerance) {

        List<TemplateFeature> features = new ArrayList<>();

        for (int i = 0; i < detectedObjects.size(); i++) {
            DetectedObject obj = detectedObjects.get(i);

            TemplateFeature feature = new TemplateFeature(
                "feature_" + i,
                obj.getClassName() != null ? obj.getClassName() : "Feature_" + i,
                obj.getCenter(),        // 相对坐标
                obj.getClassId()
            );
            // 设置容差
            feature.setTolerance(new TemplateFeature.Tolerance(defaultTolerance.x, defaultTolerance.y));

            features.add(feature);
        }

        return buildFromFeatures(templateId, cropWidth, cropHeight, features);
    }

    /**
     * 根据四个角坐标裁剪图像
     *
     * @param fullImage    完整图像
     * @param corners      四个角坐标 [TL, TR, BR, BL]
     * @param padding      扩边像素（可选，用于包含边缘特征）
     * @return 裁剪后的图像
     */
    public Mat cropImageByCorners(Mat fullImage, Point[] corners, int padding) {
        if (corners == null || corners.length != 4) {
            throw new IllegalArgumentException("Must have exactly 4 corners");
        }

        // 计算裁剪区域的边界框
        double minX = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = Double.MIN_VALUE;

        for (Point corner : corners) {
            minX = Math.min(minX, corner.x);
            maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y);
            maxY = Math.max(maxY, corner.y);
        }

        // 添加 padding
        int x0 = Math.max(0, (int)(minX - padding));
        int y0 = Math.max(0, (int)(minY - padding));
        int x1 = Math.min(fullImage.cols(), (int)(maxX + padding) + 1);
        int y1 = Math.min(fullImage.rows(), (int)(maxY + padding) + 1);

        int width = x1 - x0;
        int height = y1 - y0;

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid crop region");
        }

        // 裁剪
        Rect roi = new Rect(x0, y0, width, height);
        Mat cropped = new Mat(fullImage, roi).clone();

        logger.info("Cropped image: original=({},{}), crop_region=({},{},{}x{}), result_size=({}x{})",
            fullImage.cols(), fullImage.rows(), x0, y0, width, height, cropped.cols(), cropped.rows());

        return cropped;
    }

    /**
     * 将全局坐标转换为裁剪区域的相对坐标
     *
     * @param globalPoint      全局坐标
     * @param cropOrigin       裁剪区域原点（在全局坐标系中）
     * @return 相对坐标
     */
    public Point globalToRelative(Point globalPoint, Point cropOrigin) {
        return new Point(
            globalPoint.x - cropOrigin.x,
            globalPoint.y - cropOrigin.y
        );
    }

    /**
     * 将相对坐标转换为全局坐标
     *
     * @param relativePoint    相对坐标
     * @param cropOrigin       裁剪区域原点（在全局坐标系中）
     * @return 全局坐标
     */
    public Point relativeToGlobal(Point relativePoint, Point cropOrigin) {
        return new Point(
            relativePoint.x + cropOrigin.x,
            relativePoint.y + cropOrigin.y
        );
    }

    /**
     * 计算四个角的边界框
     *
     * @param corners  四个角坐标
     * @return [minX, minY, maxX, maxY]
     */
    public double[] getBoundingBox(Point[] corners) {
        double minX = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = Double.MIN_VALUE;

        for (Point corner : corners) {
            minX = Math.min(minX, corner.x);
            maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y);
            maxY = Math.max(maxY, corner.y);
        }

        return new double[]{minX, minY, maxX, maxY};
    }
}
