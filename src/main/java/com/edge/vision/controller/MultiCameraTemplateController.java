package com.edge.vision.controller;

import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.dto.MultiCameraCaptureResponse;
import com.edge.vision.dto.MultiCameraPreviewRequest;
import com.edge.vision.dto.MultiCameraPreviewResponse;
import com.edge.vision.dto.MultiCameraTemplateRequest;
import com.edge.vision.dto.MultiCameraTemplateResponse;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.PartCameraTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 多摄像头模板管理控制器
 * <p>
 * 建模流程：
 * 1. 调用截图 -> 返回各个摄像头的图片给前端
 * 2. 前端给每个图片框出模板整体（cropRect）
 * 3. 后端批量YOLO识别 -> 创建多个模板（每个摄像头一个）
 * <p>
 * 特点：
 * - 支持多摄像头并行建模
 * - 自动 SIFT 特征匹配
 * - YOLO 批量特征识别
 * - 模板自动关联工件类型
 */
@Tag(name = "多摄像头模板管理", description = "基于多个摄像头的模板创建和管理，支持SIFT匹配和YOLO识别")
@RestController
@RequestMapping("/api/multi-camera-template")
public class MultiCameraTemplateController {

    private static final Logger logger = LoggerFactory.getLogger(MultiCameraTemplateController.class);

    @Autowired
    private CameraService cameraService;

    @Autowired
    private PartCameraTemplateService partCameraTemplateService;

    @Autowired
    private TemplateManager templateManager;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /**
     * 第一步：截图，返回各个摄像头的图片
     */
    @PostMapping("/capture")
    @Operation(
            summary = "多摄像头截图",
            description = """
                    获取各个摄像头的当前画面，用于多摄像头建模。

                    **功能说明**：
                    - 获取所有摄像头的当前帧
                    - 保存为临时图片供后续使用
                    - 返回 Base64 编码的图片给前端

                    **建模流程**：
                    ```
                    1. 调用此接口截图 → 获取所有摄像头图片
                    2. 前端框选模板区域 → 确定每个摄像头的 cropRect
                    3. 调用 /preview 预览 → YOLO 识别特征
                    4. 调用 /save 保存 → 批量创建模板
                    ```
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "截图请求参数",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    examples = @ExampleObject(
                            name = "截图请求示例",
                            value = """
                                    {
                                      "partType": "CX756601"
                                    }
                                    """
                    )
            )
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "截图成功",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MultiCameraCaptureResponse.class),
                            examples = @ExampleObject(
                                    name = "截图成功示例",
                                    value = """
                                            {
                                              "success": true,
                                              "message": "截图成功",
                                              "partType": "CX756601",
                                              "cameras": [
                                                {
                                                  "cameraId": 0,
                                                  "imageUrl": "data:image/jpeg;base64,..."
                                                },
                                                {
                                                  "cameraId": 1,
                                                  "imageUrl": "data:image/jpeg;base64,..."
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<MultiCameraCaptureResponse> capture(@RequestBody Map<String, String> request) {
        try {
            String partType = request.get("partType");
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(MultiCameraCaptureResponse.error("请提供工件类型"));
            }

            if (!cameraService.isRunning()) {
                return ResponseEntity.ok(MultiCameraCaptureResponse.error("摄像头未启动"));
            }

            int cameraCount = cameraService.getCameraCount();
            if (cameraCount == 0) {
                return ResponseEntity.ok(MultiCameraCaptureResponse.error("没有可用的摄像头"));
            }

            // 创建临时目录
            Path tempDir = Paths.get(uploadPath, "multi-camera-temp", partType);
            Files.createDirectories(tempDir);

            List<MultiCameraCaptureResponse.CameraImage> cameras = new ArrayList<>();

            // 获取每个摄像头的当前帧
            for (int i = 0; i < cameraCount; i++) {
                Mat frame = null;
                try {
                    // 从CameraService获取当前帧
                    String base64 = cameraService.getCameraImageBase64(i);
                    if (base64 == null || base64.isEmpty()) {
                        logger.warn("Camera {} frame is null or empty", i);
                        continue;
                    }

                    // 保存临时图片
                    String filename = "camera_" + i + ".jpg";
                    Path filePath = tempDir.resolve(filename);

                    // 解码base64并保存
                    String base64Data = base64;
                    if (base64.startsWith("data:image/")) {
                        base64Data = base64.substring(base64.indexOf(',') + 1);
                    }
                    byte[] imageBytes = Base64.getDecoder().decode(base64Data);
                    Files.write(filePath, imageBytes);

                    cameras.add(new MultiCameraCaptureResponse.CameraImage(i, "data:image/jpeg;base64," + base64));

                    logger.info("Captured frame from camera {}: {}", i, filePath);

                } catch (Exception e) {
                    logger.error("Failed to capture camera {}", i, e);
                }
            }

            if (cameras.isEmpty()) {
                return ResponseEntity.ok(MultiCameraCaptureResponse.error("未能获取任何摄像头的画面"));
            }

            return ResponseEntity.ok(MultiCameraCaptureResponse.success(partType, cameras));

        } catch (Exception e) {
            logger.error("Capture failed", e);
            return ResponseEntity.ok(MultiCameraCaptureResponse.error("截图失败: " + e.getMessage()));
        }
    }

    /**
     * 第二步：预览YOLO识别结果
     */
    @PostMapping("/preview")
    @Operation(
            summary = "预览识别结果",
            description = """
                    批量YOLO识别，返回带框的图片和识别结果。

                    **功能说明**：
                    - 根据前端框选的 cropRect 裁剪图片
                    - 使用 YOLO 模型检测特征点
                    - 在图片上绘制检测框
                    - 返回识别结果供前端确认

                    **cropRect 格式**：[x, y, width, height]
                    - x, y: 裁剪区域左上角坐标
                    - width, height: 裁剪区域宽高
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "预览请求参数",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = MultiCameraPreviewRequest.class),
                    examples = @ExampleObject(
                            name = "预览请求示例",
                            value = """
                                    {
                                      "partType": "CX756601",
                                      "cameraCrops": [
                                        {
                                          "cameraId": 0,
                                          "cropRect": [100, 100, 800, 600]
                                        },
                                        {
                                          "cameraId": 1,
                                          "cropRect": [100, 100, 800, 600]
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "预览成功",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MultiCameraPreviewResponse.class),
                            examples = @ExampleObject(
                                    name = "预览成功示例",
                                    value = """
                                            {
                                              "success": true,
                                              "message": "预览成功",
                                              "partType": "CX756601",
                                              "cameras": [
                                                {
                                                  "cameraId": 0,
                                                  "imageUrl": "data:image/jpeg;base64,...",
                                                  "features": [
                                                    {
                                                      "featureId": "0_hole",
                                                      "className": "hole",
                                                      "classId": 0,
                                                      "centerX": 450.5,
                                                      "centerY": 300.2,
                                                      "width": 50.0,
                                                      "height": 50.0,
                                                      "confidence": 0.95
                                                    }
                                                  ]
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<MultiCameraPreviewResponse> preview(@RequestBody MultiCameraPreviewRequest request) {
        try {
            String partType = request.getPartType();
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(MultiCameraPreviewResponse.error("请提供工件类型"));
            }

            if (request.getCameraCrops() == null || request.getCameraCrops().isEmpty()) {
                return ResponseEntity.ok(MultiCameraPreviewResponse.error("请提供摄像头裁剪数据"));
            }

            logger.info("Previewing detections for part: {}, cameras: {}",
                partType, request.getCameraCrops().size());

            // 准备图像数据列表
            List<PartCameraTemplateService.CameraImageData> imageDataList = new ArrayList<>();
            Path tempDir = Paths.get(uploadPath, "multi-camera-temp", partType);

            for (MultiCameraPreviewRequest.CameraCropData camData : request.getCameraCrops()) {
                String tempImagePath = tempDir.resolve("camera_" + camData.getCameraId() + ".jpg").toString();

                if (!java.nio.file.Files.exists(Paths.get(tempImagePath))) {
                    logger.warn("Temp image not found for camera {}", camData.getCameraId());
                    continue;
                }

                imageDataList.add(new PartCameraTemplateService.CameraImageData(
                    camData.getCameraId(),
                    camData.getCropRect(),
                    tempImagePath
                ));
            }

            if (imageDataList.isEmpty()) {
                return ResponseEntity.ok(MultiCameraPreviewResponse.error("没有找到有效的临时图片"));
            }

            // 批量YOLO识别并获取预览结果
            List<PartCameraTemplateService.CameraPreviewResult> previewResults =
                partCameraTemplateService.previewDetections(partType, imageDataList);

            // 转换为响应格式
            List<MultiCameraPreviewResponse.CameraPreviewData> cameras = new ArrayList<>();
            for (PartCameraTemplateService.CameraPreviewResult result : previewResults) {
                List<MultiCameraPreviewResponse.FeatureInfo> features = new ArrayList<>();
                for (PartCameraTemplateService.FeatureInfo fi : result.features) {
                    features.add(new MultiCameraPreviewResponse.FeatureInfo(
                        fi.featureId, fi.className, fi.classId,
                        fi.centerX, fi.centerY, fi.width, fi.height, fi.confidence
                    ));
                }
                cameras.add(new MultiCameraPreviewResponse.CameraPreviewData(
                    result.cameraId, result.imageUrl, features
                ));
            }

            logger.info("Preview completed for {} cameras", cameras.size());

            return ResponseEntity.ok(MultiCameraPreviewResponse.success(partType, cameras));

        } catch (Exception e) {
            logger.error("Preview failed", e);
            return ResponseEntity.ok(MultiCameraPreviewResponse.error("预览失败: " + e.getMessage()));
        }
    }

    /**
     * 第三步：批量创建模板
     */
    @PostMapping("/save")
    @Operation(
            summary = "批量创建模板",
            description = """
                    根据前端框选的区域，批量YOLO识别并创建多个摄像头模板。

                    **功能说明**：
                    - 删除该工件类型的旧模板和图片
                    - 根据前端框选的 cropRect 裁剪图片
                    - 使用 YOLO 检测特征点
                    - 使用 SIFT 提取模板特征
                    - 为每个摄像头创建独立模板
                    - 自动建立工件类型与摄像头的映射关系

                    **模板命名规则**：{partType}_camera_{cameraId}
                    - 例如：CX756601_camera_0, CX756601_camera_1

                    **容差设置**：
                    - toleranceX: X 方向容差（像素）
                    - toleranceY: Y 方向容差（像素）
                    - 用于质检时判断特征是否在允许范围内
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "模板创建请求参数",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = MultiCameraTemplateRequest.class),
                    examples = @ExampleObject(
                            name = "创建模板请求示例",
                            value = """
                                    {
                                      "partType": "CX756601",
                                      "toleranceX": 20.0,
                                      "toleranceY": 20.0,
                                      "description": "CX756601工件多摄像头模板",
                                      "cameraTemplates": [
                                        {
                                          "cameraId": 0,
                                          "cropRect": [100, 100, 800, 600]
                                        },
                                        {
                                          "cameraId": 1,
                                          "cropRect": [100, 100, 800, 600]
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "创建成功",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MultiCameraTemplateResponse.class),
                            examples = @ExampleObject(
                                    name = "创建成功示例",
                                    value = """
                                            {
                                              "success": true,
                                              "message": "保存成功",
                                              "partType": "CX756601",
                                              "templates": [
                                                {
                                                  "cameraId": 0,
                                                  "templateId": "CX756601_camera_0",
                                                  "imagePath": "uploads/templates/CX756601_camera_0.jpg",
                                                  "featureCount": 5
                                                },
                                                {
                                                  "cameraId": 1,
                                                  "templateId": "CX756601_camera_1",
                                                  "imagePath": "uploads/templates/CX756601_camera_1.jpg",
                                                  "featureCount": 5
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<MultiCameraTemplateResponse> save(@RequestBody MultiCameraTemplateRequest request) {
        try {
            String partType = request.getPartType();
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(MultiCameraTemplateResponse.error("请提供工件类型"));
            }

            if (request.getCameraTemplates() == null || request.getCameraTemplates().isEmpty()) {
                return ResponseEntity.ok(MultiCameraTemplateResponse.error("请提供摄像头模板数据"));
            }

            logger.info("Creating multi-camera templates for part: {}, cameras: {}",
                partType, request.getCameraTemplates().size());

            // 先删除旧的模板和图片
            deletePartTypeImages(partType);
            partCameraTemplateService.removePartTemplates(partType);
            logger.info("Cleared old templates and images for part: {}", partType);

            // 准备图像数据列表
            List<PartCameraTemplateService.CameraImageData> imageDataList = new ArrayList<>();
            Path tempDir = Paths.get(uploadPath, "multi-camera-temp", partType);

            for (MultiCameraTemplateRequest.CameraTemplateData camData : request.getCameraTemplates()) {
                String tempImagePath = tempDir.resolve("camera_" + camData.getCameraId() + ".jpg").toString();

                if (!Files.exists(Paths.get(tempImagePath))) {
                    logger.warn("Temp image not found for camera {}", camData.getCameraId());
                    continue;
                }

                imageDataList.add(new PartCameraTemplateService.CameraImageData(
                    camData.getCameraId(),
                    camData.getCropRect(),
                    tempImagePath
                ));
            }

            if (imageDataList.isEmpty()) {
                return ResponseEntity.ok(MultiCameraTemplateResponse.error("没有找到有效的临时图片"));
            }

            // 批量创建模板
            List<com.edge.vision.core.template.model.Template> templates = partCameraTemplateService.createTemplatesBatch(
                partType,
                imageDataList,
                request.getToleranceX(),
                request.getToleranceY()
            );

            if (templates.isEmpty()) {
                return ResponseEntity.ok(MultiCameraTemplateResponse.error("未能创建任何模板，请检查YOLO识别结果"));
            }

            // 构建响应
            List<MultiCameraTemplateResponse.SavedTemplateInfo> templateInfos = new ArrayList<>();
            for (com.edge.vision.core.template.model.Template template : templates) {
                Integer cameraId = (Integer) template.getMetadata().get("cameraId");
                templateInfos.add(new MultiCameraTemplateResponse.SavedTemplateInfo(
                    cameraId != null ? cameraId : -1,
                    template.getTemplateId(),
                    template.getImagePath(),
                    template.getFeatures() != null ? template.getFeatures().size() : 0
                ));
            }

            logger.info("Successfully created {} templates for part {}", templates.size(), partType);

            return ResponseEntity.ok(MultiCameraTemplateResponse.success(partType, templateInfos));

        } catch (Exception e) {
            logger.error("Save failed", e);
            return ResponseEntity.ok(MultiCameraTemplateResponse.error("保存失败: " + e.getMessage()));
        }
    }

    /**
     * 获取工件的所有摄像头模板信息
     */
    @GetMapping("/{partType}")
    @Operation(
            summary = "获取工件模板信息",
            description = "获取指定工件的所有摄像头模板映射信息，包括摄像头ID和对应的模板ID"
    )
    @Parameter(
            name = "partType",
            description = "工件类型，如：CX756601",
            required = true,
            example = "CX756601"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "查询成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "查询成功示例",
                                    value = """
                                            {
                                              "success": true,
                                              "partType": "CX756601",
                                              "cameras": [
                                                {"cameraId": 0, "templateId": "CX756601_camera_0"},
                                                {"cameraId": 1, "templateId": "CX756601_camera_1"}
                                              ],
                                              "cameraCount": 2
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getTemplates(
            @Parameter(description = "工件类型", required = true, example = "CX756601")
            @PathVariable String partType) {
        try {
            Map<Integer, String> cameraTemplates = partCameraTemplateService.getCameraTemplates(partType);

            List<Map<String, Object>> templateList = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : cameraTemplates.entrySet()) {
                Map<String, Object> info = new java.util.HashMap<>();
                info.put("cameraId", entry.getKey());
                info.put("templateId", entry.getValue());
                templateList.add(info);
            }

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", true);
            response.put("partType", partType);
            response.put("cameras", templateList);
            response.put("cameraCount", templateList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Get templates failed", e);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 删除工件的所有模板
     */
    @DeleteMapping("/{partType}")
    @Operation(
            summary = "删除工件模板",
            description = """
                    删除指定工件的所有摄像头模板及关联图片。

                    **删除内容**：
                    - 所有摄像头模板数据
                    - 模板关联的图片文件
                    - 工件类型与摄像头的映射关系

                    **注意事项**：
                    - 删除操作不可恢复
                    - 删除后需要重新建模才能使用质检功能
                    """
    )
    @Parameter(
            name = "partType",
            description = "工件类型，如：CX756601",
            required = true,
            example = "CX756601"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "删除成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "删除成功示例",
                                    value = """
                                            {
                                              "success": true,
                                              "message": "删除成功",
                                              "deletedImages": 2
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> deleteTemplates(
            @Parameter(description = "工件类型", required = true, example = "CX756601")
            @PathVariable String partType) {
        try {
            // 先删除图片
            int deletedImages = deletePartTypeImages(partType);
            logger.info("Deleted {} images for part: {}", deletedImages, partType);

            // 再删除模板
            partCameraTemplateService.removePartTemplates(partType);

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", true);
            response.put("message", "删除成功");
            response.put("deletedImages", deletedImages);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Delete templates failed", e);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 删除指定工件类型的所有模板图片
     */
    private int deletePartTypeImages(String partType) {
        int deletedCount = 0;
        try {
            // 获取该工件的所有模板
            List<com.edge.vision.core.template.model.Template> templates =
                templateManager.getTemplatesByPartType(partType);

            for (com.edge.vision.core.template.model.Template template : templates) {
                String imagePath = template.getImagePath();
                if (imagePath != null && !imagePath.isEmpty()) {
                    try {
                        Path filePath = Paths.get(imagePath);
                        if (Files.exists(filePath)) {
                            Files.delete(filePath);
                            deletedCount++;
                            logger.info("Deleted template image: {}", imagePath);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to delete image: {}", imagePath, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to delete images for part: {}", partType, e);
        }
        return deletedCount;
    }

    /**
     * 获取所有工件类型
     */
    @GetMapping("/part-types")
    @Operation(
            summary = "获取所有工件类型",
            description = """
                    获取所有已建模的工件类型列表及其摄像头数量。

                    **返回信息**：
                    - 工件类型名称
                    - 每个工件类型关联的摄像头数量

                    **使用场景**：
                    - 前端下拉选择工件类型
                    - 查询系统已建模的工件列表
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "查询成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "查询成功示例",
                                    value = """
                                            {
                                              "success": true,
                                              "partTypes": [
                                                {"partType": "CX756601", "cameraCount": 2},
                                                {"partType": "EKS", "cameraCount": 1}
                                              ]
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getAllPartTypes() {
        try {
            java.util.Set<String> partTypes = templateManager.getAllPartTypes();

            // 为每个工件类型获取摄像头数量
            List<Map<String, Object>> partTypeList = new ArrayList<>();
            for (String partType : partTypes) {
                Map<String, Object> info = new java.util.HashMap<>();
                info.put("partType", partType);
                info.put("cameraCount", partCameraTemplateService.getCameraCount(partType));
                partTypeList.add(info);
            }

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", true);
            response.put("partTypes", partTypeList);
            response.put("count", partTypeList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Get part types failed", e);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}
