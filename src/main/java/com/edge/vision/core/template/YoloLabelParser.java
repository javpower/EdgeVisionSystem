package com.edge.vision.core.template;

import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * YOLO 格式标注解析器
 * <p>
 * YOLO 标注格式:
 * <class_id> <center_x> <center_y> <width> <height>
 * <p>
 * 坐标值为归一化值 (0.0 - 1.0)
 */
public class YoloLabelParser {
    private static final Logger logger = LoggerFactory.getLogger(YoloLabelParser.class);

    /**
     * 解析 YOLO 标注文件
     *
     * @param labelPath 标注文件路径
     * @param imageWidth 图片宽度（像素）
     * @param imageHeight 图片高度（像素）
     * @return 检测对象列表
     */
    public static List<DetectedObject> parse(String labelPath, int imageWidth, int imageHeight) throws IOException {
        List<DetectedObject> objects = new ArrayList<>();
        Path path = Path.of(labelPath);

        if (!Files.exists(path)) {
            logger.warn("Label file not found: {}", labelPath);
            return objects;
        }

        List<String> lines = Files.readAllLines(path);
        int lineNumber = 0;

        for (String line : lines) {
            lineNumber++;
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            try {
                DetectedObject obj = parseLine(line, imageWidth, imageHeight);
                if (obj != null) {
                    objects.add(obj);
                }
            } catch (Exception e) {
                logger.error("Failed to parse line {} in {}: {}", lineNumber, labelPath, line);
            }
        }

        logger.info("Parsed {} objects from {}", objects.size(), labelPath);
        return objects;
    }

    /**
     * 解析单行标注
     *
     * @param line 标注行
     * @param imageWidth 图片宽度（像素）
     * @param imageHeight 图片高度（像素）
     * @return 检测对象
     */
    private static DetectedObject parseLine(String line, int imageWidth, int imageHeight) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5) {
            logger.warn("Invalid YOLO format: {}", line);
            return null;
        }

        int classId = Integer.parseInt(parts[0]);

        // 归一化坐标
        double normCenterX = Double.parseDouble(parts[1]);
        double normCenterY = Double.parseDouble(parts[2]);
        double normWidth = Double.parseDouble(parts[3]);
        double normHeight = Double.parseDouble(parts[4]);

        // 转换为像素坐标
        double centerX = normCenterX * imageWidth;
        double centerY = normCenterY * imageHeight;
        double width = normWidth * imageWidth;
        double height = normHeight * imageHeight;

        DetectedObject obj = new DetectedObject();
        obj.setClassId(classId);
        obj.setCenter(new Point(centerX, centerY));
        obj.setWidth(width);
        obj.setHeight(height);

        return obj;
    }
}
