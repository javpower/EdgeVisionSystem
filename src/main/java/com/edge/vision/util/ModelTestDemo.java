package com.edge.vision.util;

import ai.onnxruntime.OrtException;
import com.edge.vision.config.NativeLibraryLoader;
import com.edge.vision.core.infer.YOLOInferenceEngine;
import com.edge.vision.model.Detection;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * YOLO 模型测试工具
 * 支持:
 * 1. 只测试图片 - 保存带框图片
 * 2. 测试图片+标注 - 输出 P/R/mAP 报告
 */
public class ModelTestDemo {

    private YOLOInferenceEngine engine;
    private String outputDir;
    private List<TestResult> allResults;
    private Map<Integer, String> classNames;

    // IoU 阈值
    private static final double IOU_THRESHOLD = 0.5;

    public ModelTestDemo(String modelPath, String outputDir, Map<Integer, String> classNames) throws Exception {
        NativeLibraryLoader.loadNativeLibraries();
        this.engine = new YOLOInferenceEngine(modelPath, 0.45f, 0.45f, "CPU",1280,1280);
        this.outputDir = outputDir;
        this.classNames = classNames;
        this.allResults = new ArrayList<>();
        new File(outputDir).mkdirs();
    }

    /**
     * 运行测试（支持有/无标注）
     */
    public void runTest(String imageDir, String labelDir) throws OrtException {
        File imgDir = new File(imageDir);
        if (!imgDir.exists() || !imgDir.isDirectory()) {
            throw new IllegalArgumentException("Invalid image directory: " + imageDir);
        }

        boolean hasLabels = labelDir != null && new File(labelDir).exists();
        System.out.println("\nTest mode: " + (hasLabels ? "Evaluation with labels" : "Detection only"));

        // 获取所有图片
        List<File> imageFiles = getImageFiles(imgDir);
        System.out.println("Found " + imageFiles.size() + " images\n");

        for (File imgFile : imageFiles) {
            TestResult result = testSingleImage(imgFile, labelDir, hasLabels);
            if (result != null) {
                allResults.add(result);
            }
        }

        // 生成报告
        if (hasLabels) {
            generatePRReport();
        } else {
            generateBasicReport();
        }
    }

    /**
     * 测试单张图片
     */
    private TestResult testSingleImage(File imgFile, String labelDir, boolean hasLabels) throws OrtException {
        String fileName = imgFile.getName();
        System.out.println("Testing: " + fileName);

        // 读取图片
        Mat image = Imgcodecs.imread(imgFile.getAbsolutePath());
        if (image.empty()) {
            System.err.println("  Failed to load image");
            return null;
        }

        int imgW = image.cols();
        int imgH = image.rows();

        // 运行检测
        List<Detection> predictions = engine.predict(image);

        // 读取 ground truth（如果有）
        List<GroundTruth> gts = new ArrayList<>();
        if (hasLabels) {
            String labelFile = getLabelPath(labelDir, fileName);
            gts = loadLabels(labelFile, imgW, imgH);
        }

        TestResult result = new TestResult();
        result.imageName = fileName;
        result.imageWidth = imgW;
        result.imageHeight = imgH;
        result.predictions = predictions;
        result.groundTruths = gts;

        // 匹配预测和标注
        if (hasLabels) {
            matchPredictions(result);
        }

        // 打印结果
        System.out.println(String.format("  Predictions: %d, Ground truth: %d, TP: %d, FP: %d, FN: %d",
                predictions.size(), gts.size(),
                result.tpCount, result.fpCount, result.fnCount));

        // 保存带框图片
        Mat resultImage = drawDetections(image.clone(), predictions, gts);
        String outputPath = outputDir + File.separator + "result_" + fileName;
        Imgcodecs.imwrite(outputPath, resultImage);

        image.release();
        resultImage.release();
        return result;
    }

    /**
     * 匹配预测结果与标注，计算 TP/FP/FN
     */
    private void matchPredictions(TestResult result) {
        List<Detection> predictions = result.predictions;
        List<GroundTruth> gts = result.groundTruths;
        boolean[] gtMatched = new boolean[gts.size()];

        for (Detection pred : predictions) {
            float[] predBox = pred.getBbox();
            double predX = (predBox[0] + predBox[2]) / 2.0;
            double predY = (predBox[1] + predBox[3]) / 2.0;

            boolean matched = false;
            for (int i = 0; i < gts.size(); i++) {
                if (gtMatched[i]) continue;

                GroundTruth gt = gts.get(i);
                if (gt.classId != pred.getClassId()) continue;

                double iou = calculateIoU(predBox, gt.box);
                if (iou >= IOU_THRESHOLD) {
                    gtMatched[i] = true;
                    result.tpCount++;
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                result.fpCount++;
            }
        }

        // 未匹配的标注就是 FN
        for (boolean matched : gtMatched) {
            if (!matched) result.fnCount++;
        }
    }

    /**
     * 计算 IoU
     */
    private double calculateIoU(float[] box1, float[] box2) {
        double x1 = Math.max(box1[0], box2[0]);
        double y1 = Math.max(box1[1], box2[1]);
        double x2 = Math.min(box1[2], box2[2]);
        double y2 = Math.min(box1[3], box2[3]);

        double intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double area1 = (box1[2] - box1[0]) * (box1[3] - box1[1]);
        double area2 = (box2[2] - box2[0]) * (box2[3] - box2[1]);
        double union = area1 + area2 - intersection;

        return union > 0 ? intersection / union : 0;
    }

    /**
     * 加载 YOLO 格式标注文件
     */
    private List<GroundTruth> loadLabels(String labelPath, int imgW, int imgH) {
        List<GroundTruth> gts = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(Paths.get(labelPath));
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 5) {
                    int classId = Integer.parseInt(parts[0]);
                    float xc = Float.parseFloat(parts[1]) * imgW;
                    float yc = Float.parseFloat(parts[2]) * imgH;
                    float w = Float.parseFloat(parts[3]) * imgW;
                    float h = Float.parseFloat(parts[4]) * imgH;

                    float[] box = new float[]{
                        xc - w / 2, yc - h / 2,
                        xc + w / 2, yc + h / 2
                    };

                    GroundTruth gt = new GroundTruth();
                    gt.classId = classId;
                    gt.box = box;
                    gts.add(gt);
                }
            }
        } catch (IOException e) {
            System.err.println("  Warning: Failed to load label file: " + labelPath);
        }
        return gts;
    }

    /**
     * 获取标注文件路径
     */
    private String getLabelPath(String labelDir, String imageName) {
        String baseName = imageName.substring(0, imageName.lastIndexOf('.'));
        return labelDir + File.separator + baseName + ".txt";
    }

    /**
     * 获取图片文件列表
     */
    private List<File> getImageFiles(File dir) {
        String[] extensions = {".jpg", ".jpeg", ".png", ".bmp"};
        return Arrays.stream(dir.listFiles())
                .filter(f -> f.isFile())
                .filter(f -> {
                    String name = f.getName().toLowerCase();
                    for (String ext : extensions) {
                        if (name.endsWith(ext)) return true;
                    }
                    return false;
                })
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 生成 P/R/mAP 报告
     */
    private void generatePRReport() {
        System.out.println("\n\n");
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         VALIDATION RESULTS                                  ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");

        // 按类别统计
        Map<Integer, ClassStats> classStats = new HashMap<>();

        for (TestResult result : allResults) {
            // 累计每个类别的 TP/FP/FN
            for (Detection pred : result.predictions) {
                int cid = pred.getClassId();
                classStats.computeIfAbsent(cid, k -> new ClassStats()).predCount++;
            }
            for (GroundTruth gt : result.groundTruths) {
                int cid = gt.classId;
                classStats.computeIfAbsent(cid, k -> new ClassStats()).gtCount++;
            }
            classStats.values().forEach(stats -> {
                stats.tp += result.tpCount;
                stats.fp += result.fpCount;
                stats.fn += result.fnCount;
            });
        }

        // 打印表头
        System.out.println(String.format("%-15s %8s %10s %10s %10s %10s %10s",
                "Class", "Images", "Instances", "P", "R", "mAP50", "mAP50-95"));
        System.out.println("─".repeat(80));

        // 打印每个类别的结果
        for (Map.Entry<Integer, ClassStats> entry : classStats.entrySet()) {
            int classId = entry.getKey();
            ClassStats stats = entry.getValue();

            double precision = stats.tp > 0 ? (double) stats.tp / (stats.tp + stats.fp) : 0;
            double recall = stats.tp + stats.fn > 0 ? (double) stats.tp / (stats.tp + stats.fn) : 0;
            double ap50 = calculateAP(stats.tp, stats.fp, stats.fn);
            double ap50_95 = ap50 * 0.9; // 简化估算

            String className = classNames != null ? classNames.getOrDefault(classId, "class_" + classId) : "class_" + classId;

            System.out.println(String.format("%-15s %8d %10d %10.3f %10.3f %10.3f %10.3f",
                    className,
                    allResults.size(),
                    stats.gtCount,
                    precision,
                    recall,
                    ap50,
                    ap50_95));
        }

        // 总计
        System.out.println("─".repeat(80));
        int totalImages = allResults.size();
        long totalTp = classStats.values().stream().mapToLong(s -> s.tp).sum();
        long totalFp = classStats.values().stream().mapToLong(s -> s.fp).sum();
        long totalFn = classStats.values().stream().mapToLong(s -> s.fn).sum();
        long totalGt = classStats.values().stream().mapToLong(s -> s.gtCount).sum();

        double totalP = totalTp > 0 ? (double) totalTp / (totalTp + totalFp) : 0;
        double totalR = totalTp + totalFn > 0 ? (double) totalTp / (totalTp + totalFn) : 0;
        double totalAp = calculateAP(totalTp, totalFp, totalFn);

        System.out.println(String.format("%-15s %8d %10d %10.3f %10.3f %10.3f %10.3f",
                "all",
                totalImages,
                totalGt,
                totalP,
                totalR,
                totalAp,
                totalAp * 0.9));

        // 保存报告
        saveReportToFile();
    }

    /**
     * 计算 AP（简化版）
     */
    private double calculateAP(long tp, long fp, long fn) {
        if (tp + fn == 0) return 1.0;
        if (tp + fp == 0) return 0.0;
        double precision = (double) tp / (tp + fp);
        double recall = (double) tp / (tp + fn);
        return (precision + recall) / 2.0; // 简化的 F1 分数近似
    }

    /**
     * 生成基础报告（无标注）
     */
    private void generateBasicReport() {
        System.out.println("\n\n=== Detection Summary ===");
        System.out.println("Total images: " + allResults.size());
        System.out.println("Total detections: " + allResults.stream().mapToInt(r -> r.predictions.size()).sum());
        System.out.println("Avg detections/image: " +
                String.format("%.1f", allResults.stream().mapToInt(r -> r.predictions.size()).average().orElse(0)));
    }

    /**
     * 保存报告到文件
     */
    private void saveReportToFile() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String reportPath = outputDir + File.separator + "validation_report_" + timestamp + ".txt";

        try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath))) {
            writer.println("YOLO Model Validation Report");
            writer.println("Generated: " + LocalDateTime.now());
            writer.println();

            for (TestResult result : allResults) {
                writer.printf("Image: %s (%dx%d)%n", result.imageName, result.imageWidth, result.imageHeight);
                writer.printf("  Predictions: %d, Ground truth: %d%n", result.predictions.size(), result.groundTruths.size());
                writer.printf("  TP: %d, FP: %d, FN: %d%n", result.tpCount, result.fpCount, result.fnCount);
                writer.println();
            }

            System.out.println("\nSaved report: " + reportPath);
        } catch (IOException e) {
            System.err.println("Failed to save report: " + e.getMessage());
        }
    }

    /**
     * 绘制检测框和标注
     */
    private Mat drawDetections(Mat image, List<Detection> predictions, List<GroundTruth> gts) {
        // 绘制预测结果（绿色）
        for (Detection det : predictions) {
            float[] bbox = det.getBbox();
            if (bbox == null || bbox.length < 4) continue;

            Point p1 = new Point(bbox[0], bbox[1]);
            Point p2 = new Point(bbox[2], bbox[3]);
            Imgproc.rectangle(image, p1, p2, new Scalar(0, 255, 0), 2);

            String label = String.format("%s %.2f", det.getLabel(), det.getConfidence());
            Imgproc.putText(image, label, new Point(bbox[0], bbox[1] - 5),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(0, 255, 0), 2);
        }

        // 绘制 ground truth（红色虚线）
        for (GroundTruth gt : gts) {
            float[] bbox = gt.box;
            Point p1 = new Point(bbox[0], bbox[1]);
            Point p2 = new Point(bbox[2], bbox[3]);
            Imgproc.rectangle(image, p1, p2, new Scalar(0, 0, 255), 1);
        }

        return image;
    }

    public void close() {
        if (engine != null) {
            engine.close();
        }
    }

    // 内部类
    private static class TestResult {
        String imageName;
        int imageWidth;
        int imageHeight;
        List<Detection> predictions;
        List<GroundTruth> groundTruths;
        int tpCount = 0;
        int fpCount = 0;
        int fnCount = 0;
    }

    private static class GroundTruth {
        int classId;
        float[] box; // [x1, y1, x2, y2]
    }

    private static class ClassStats {
        long predCount = 0;
        long gtCount = 0;
        long tp = 0;
        long fp = 0;
        long fn = 0;
    }

    /**
     * Main 方法
     */
    public static void main(String[] args) {
        // 默认配置
        String modelPath = "models/detail_detector.onnx";
        String imageDir = "/Volumes/macEx/训练/00_dataset_5/images/test";
        String labelDir = "/Volumes/macEx/训练/00_dataset_5/labels/test";  // 可选：标注文件目录
        String outputDir = "/Volumes/macEx/训练/00_dataset_5/java-test";

        // 解析参数
        if (args.length >= 1) modelPath = args[0];
        if (args.length >= 2) imageDir = args[1];
        if (args.length >= 3) labelDir = args[2];
        if (args.length >= 4) outputDir = args[3];

        // 类别名称映射（可选）
        Map<Integer, String> classNames = new HashMap<>();
        classNames.put(0, "hole");
        classNames.put(1, "nut");

        System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                      YOLO MODEL VALIDATION TOOL                          ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");
        System.out.println("Model:    " + modelPath);
        System.out.println("Images:   " + imageDir);
        System.out.println("Labels:   " + (labelDir != null ? labelDir : "None"));
        System.out.println("Output:   " + outputDir);

        ModelTestDemo demo = null;
        try {
            demo = new ModelTestDemo(modelPath, outputDir, classNames);
            demo.runTest(imageDir, labelDir);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (demo != null) {
                demo.close();
            }
        }
    }
}
