package com.edge.vision.controller;

import com.edge.vision.service.StitchConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拼接配置控制器 (V6 通用版 - 每个摄像头支持左右切割)
 * <p>
 * 提供拼接策略配置和手动拼接参数调节的 API
 * 移除了 auto 策略，只保留 simple 和 manual
 * manual 模式使用通用的数组切片方法，每个摄像头支持独立的左右切割参数
 */
@RestController
@RequestMapping("/api/stitch")
@Tag(name = "拼接配置", description = "拼接策略切换和手动拼接参数调节（通用版：基于数组切片，每个摄像头支持左右切割），配置自动持久化到 data/stitch-config.json")
public class StitchConfigController {
    private static final Logger logger = LoggerFactory.getLogger(StitchConfigController.class);

    @Autowired
    private StitchConfigService stitchConfigService;

    /**
     * 获取所有拼接配置
     */
    @GetMapping("/config")
    @Operation(
            summary = "获取所有拼接配置",
            description = """
                    获取当前系统的所有拼接配置信息，包括当前策略、可用策略等。

                    **返回字段说明**：
                    | 字段 | 类型 | 说明 |
                    |------|------|------|
                    | currentStrategy | string | 当前拼接策略：simple/manual |
                    | availableStrategies | array | 可用的拼接策略列表 |
                    | blendWidth | number | 融合区域宽度（像素）|
                    | enableBlend | boolean | 是否启用融合 |
                    | configFilePath | string | 手动配置文件路径 |
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "获取成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "currentStrategy": "manual",
                                                "availableStrategies": ["simple", "manual"],
                                                "blendWidth": 100,
                                                "enableBlend": true,
                                                "configFilePath": "/path/to/data/stitch-config.json"
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> config = stitchConfigService.getAllConfigs();
            response.put("status", "success");
            response.put("data", config);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get config", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取当前拼接策略
     */
    @GetMapping("/strategy")
    @Operation(
            summary = "获取当前拼接策略",
            description = """
                    获取当前正在使用的拼接策略。

                    **拼接策略说明**：

                    | 策略 | 说明 | 适用场景 |
                    |------|------|----------|
                    | simple | 简单水平拼接，支持边缘融合 | 摄像头位置固定，无需对齐 |
                    | manual | 手动调节拼接参数（数组切片） | 需要精确控制拼接效果 |
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "获取成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "strategy": "manual",
                                                "available": ["simple", "manual"]
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getStrategy() {
        Map<String, Object> response = new HashMap<>();
        try {
            String strategy = stitchConfigService.getCurrentStrategy();
            response.put("status", "success");
            Map<String, Object> data = new HashMap<>();
            data.put("strategy", strategy);
            data.put("available", List.of("simple", "manual"));
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get strategy", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 设置拼接策略
     */
    @PostMapping("/strategy")
    @Operation(
            summary = "设置拼接策略",
            description = """
                    切换拼接策略。

                    **策略说明**：

                    | 策略 | 说明 | 速度 | 效果 |
                    |------|------|------|------|
                    | simple | 简单水平拼接 | 快 | 一般 |
                    | manual | 手动调节参数（数组切片）| 快 | 可调 |

                    **切换到 manual 模式后**：
                    - 自动加载 `data/stitch-config.json` 中的配置
                    - 如果文件不存在，使用默认配置
                    - 可通过 `/api/stitch/manual` 接口调节参数
                    - 参数修改会自动保存到配置文件
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "要设置的拼接策略",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SetStrategyRequest.class),
                    examples = {
                            @ExampleObject(
                                    name = "切换到简单拼接",
                                    value = """
                                            {
                                              "strategy": "simple"
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "切换到手动拼接",
                                    value = """
                                            {
                                              "strategy": "manual"
                                            }
                                            """
                            )
                    }
            )
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "策略设置成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "strategy": "manual"
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "无效的策略名称",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "error",
                                              "message": "Invalid strategy: xxx"
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> setStrategy(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String strategy = request.get("strategy");
            if (strategy == null || strategy.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Strategy is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            stitchConfigService.setStrategy(strategy);

            response.put("status", "success");
            Map<String, Object> data = new HashMap<>();
            data.put("strategy", strategy);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            logger.error("Failed to set strategy", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取手动拼接配置
     */
    @GetMapping("/manual")
    @Operation(
            summary = "获取手动拼接配置（通用版）",
            description = """
                    获取当前手动拼接模式的配置参数。

                    **配置参数说明**：

                    | 参数 | 类型 | 说明 |
                    |------|------|------|
                    | index | number | 摄像头索引（从0开始）|
                    | x1 | number | 左切割线位置（保留从 x1 到右侧的区域）|
                    | x2 | number | 右切割线位置（保留从左侧到 x2 的区域）|
                    | y | number | 截取起始 Y 坐标 |
                    | h | number | 截取高度 |

                    **拼接原理**：
                    每个摄像头保留 [y, y+h] 行，[x1, x2) 列
                    所有切片后的图像水平拼接

                    **典型值参考**：
                    - 假设原始图像 5472x3648
                    - 摄像头1：x1=0, x2=4000, y=0, h=3648 → 保留左 73%
                    - 摄像头2：x1=1600, x2=5472, y=0, h=3648 → 保留右 73%
                    - 摄像头3：x1=1200, x2=4200, y=0, h=3648 → 保留中间部分
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "获取成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "strategy": "manual",
                                                "cameras": [
                                                  {
                                                    "index": 0,
                                                    "x1": 0,
                                                    "x2": 4000,
                                                    "y": 0,
                                                    "h": 3648
                                                  },
                                                  {
                                                    "index": 1,
                                                    "x1": 1600,
                                                    "x2": 5472,
                                                    "y": 0,
                                                    "h": 3648
                                                  },
                                                  {
                                                    "index": 2,
                                                    "x1": 1200,
                                                    "x2": 4200,
                                                    "y": 0,
                                                    "h": 3648
                                                  }
                                                ]
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getManualConfig() {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> config = stitchConfigService.getManualConfig();
            response.put("status", "success");
            response.put("data", config);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get manual config", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 更新单个摄像头的手动拼接配置
     */
    @PostMapping("/manual/{cameraIndex}")
    @Operation(
            summary = "更新单个摄像头配置（通用版）",
            description = """
                    更新指定摄像头的手动拼接参数。

                    **参数说明**：

                    | 参数 | 类型 | 说明 | 默认值 |
                    |------|------|------|--------|
                    | x1 | number | 左切割线位置（像素）| 0 |
                    | x2 | number | 右切割线位置（像素）| 5472 |
                    | y | number | 截取起始 Y 坐标（像素）| 0 |
                    | h | number | 截取高度（像素）| 3648 |

                    **使用场景**：

                    1. **调整切割位置**：
                       - 摄像头1：x1=0, x2=4000 保留左侧
                       - 摄像头2：x1=1600, x2=5472 保留右侧
                       - 摄像头3：x1=1200, x2=4200 保留中间

                    2. **处理上下偏移**：
                       - 设置 y=10 从第 10 行开始截取
                       - 设置 h=3600 截取 3600 像素高度

                    **注意**：修改后自动保存到 `data/stitch-config.json`
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "摄像头配置参数（所有参数都是可选的，只更新提供的字段）",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ManualConfigRequest.class),
                    examples = {
                            @ExampleObject(
                                    name = "调整切割位置",
                                    value = """
                                            {
                                              "x1": 0,
                                              "x2": 4000
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "处理上下偏移",
                                    value = """
                                            {
                                              "y": 10,
                                              "h": 3600
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "完整配置",
                                    value = """
                                            {
                                              "x1": 0,
                                              "x2": 4000,
                                              "y": 0,
                                              "h": 3648
                                            }
                                            """
                            )
                    }
            )
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "更新成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "cameraIndex": 1,
                                                "message": "Config updated successfully"
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "当前不是手动模式",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "error",
                                              "message": "Manual config can only be updated when strategy is 'manual'"
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> updateManualConfig(
            @Parameter(
                    description = "摄像头索引（从 0 开始）",
                    required = true,
                    example = "1",
                    schema = @Schema(type = "integer", minimum = "0")
            )
            @PathVariable int cameraIndex,
            @RequestBody Map<String, Object> params) {
        Map<String, Object> response = new HashMap<>();
        try {
            stitchConfigService.updateManualConfig(cameraIndex, params);

            response.put("status", "success");
            Map<String, Object> data = new HashMap<>();
            data.put("cameraIndex", cameraIndex);
            data.put("message", "Config updated successfully");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            logger.error("Failed to update manual config for camera {}", cameraIndex, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 批量更新手动拼接配置
     */
    @PostMapping("/manual/batch")
    @Operation(
            summary = "批量更新摄像头配置（通用版）",
            description = """
                    一次性更新多个摄像头的拼接参数。

                    **使用场景**：
                    - 初始化多个摄像头的配置
                    - 从配置文件导入设置
                    - 批量调整参数

                    **请求格式**：
                    ```json
                    {
                      "cameras": [
                        { "index": 0, "x1": 0, "x2": 4000, "y": 0, "h": 3648 },
                        { "index": 1, "x1": 1600, "x2": 5472, "y": 0, "h": 3648 }
                      ]
                    }
                    ```
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "批量摄像头配置",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = BatchManualConfigRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "cameras": [
                                        {
                                          "index": 0,
                                          "x1": 0,
                                          "x2": 4000,
                                          "y": 0,
                                          "h": 3648
                                        },
                                        {
                                          "index": 1,
                                          "x1": 1600,
                                          "x2": 5472,
                                          "y": 0,
                                          "h": 3648
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
                    description = "批量更新成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "count": 2,
                                                "message": "Batch config updated successfully"
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求格式错误或当前不是手动模式"
            )
    })
    public ResponseEntity<Map<String, Object>> updateManualConfigBatch(@RequestBody Map<String, List<Map<String, Object>>> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> cameras = request.get("cameras");
            if (cameras == null || cameras.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Cameras array is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            stitchConfigService.updateManualConfigBatch(cameras);

            response.put("status", "success");
            Map<String, Object> data = new HashMap<>();
            data.put("count", cameras.size());
            data.put("message", "Batch config updated successfully");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            logger.error("Failed to batch update manual config", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 重置手动拼接配置为默认值
     */
    @PostMapping("/manual/reset")
    @Operation(
            summary = "重置为默认配置",
            description = """
                    将所有摄像头的手动拼接参数重置为默认值。

                    **默认值**（假设图像宽度5472，高度3648）：
                    - 第一个摄像头 (index=0)：x1=0, x2=4000
                    - 中间摄像头：x1=1200, x2=4200
                    - 最后一个摄像头：x1=1600, x2=5472
                    - y: 0
                    - h: 3648

                    **使用场景**：
                    - 配置混乱时重新开始
                    - 测试默认拼接效果
                    - 清除之前的自定义设置
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "重置成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "message": "Manual config reset to default"
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> resetManualConfig() {
        Map<String, Object> response = new HashMap<>();
        try {
            stitchConfigService.resetManualConfig();

            response.put("status", "success");
            Map<String, Object> data = new HashMap<>();
            data.put("message", "Manual config reset to default");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to reset manual config", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取拼接参数说明
     */
    @GetMapping("/params")
    @Operation(
            summary = "获取拼接参数说明",
            description = """
                    获取拼接策略和参数的详细说明文档，包括使用示例。
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "获取成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "strategies": {
                                                  "simple": "简单水平拼接 - 适用于摄像头位置固定的场景",
                                                  "manual": "手动拼接（数组切片）- 每个摄像头支持独立的左右切割参数"
                                                },
                                                "manualParams": {
                                                  "x1": "左切割线位置 - 保留从 x1 到右侧的区域",
                                                  "x2": "右切割线位置 - 保留从左侧到 x2 的区域",
                                                  "y": "截取起始 Y 坐标 - 从哪一行开始截取",
                                                  "h": "截取高度 - 截取多少行"
                                                },
                                                "examples": [...]
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getParamsInfo() {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> data = new HashMap<>();

            Map<String, String> strategies = new HashMap<>();
            strategies.put("simple", "简单水平拼接 - 适用于摄像头位置固定的场景");
            strategies.put("manual", "手动拼接（数组切片）- 每个摄像头支持独立的左右切割参数");
            data.put("strategies", strategies);

            Map<String, String> manualParams = new HashMap<>();
            manualParams.put("x1", "左切割线位置 - 保留从 x1 到右侧的区域");
            manualParams.put("x2", "右切割线位置 - 保留从左侧到 x2 的区域");
            manualParams.put("y", "截取起始 Y 坐标 - 从哪一行开始截取");
            manualParams.put("h", "截取高度 - 截取多少行");
            data.put("manualParams", manualParams);

            List<Map<String, Object>> examples = new ArrayList<>();

            Map<String, Object> example1 = new HashMap<>();
            example1.put("description", "两个摄像头拼接，原始图像 5472x3648，各保留 73% 后拼接");
            example1.put("config", Map.of(
                    "cameras", List.of(
                            Map.of("index", 0, "x1", 0, "x2", 4000, "y", 0, "h", 3648),
                            Map.of("index", 1, "x1", 1600, "x2", 5472, "y", 0, "h", 3648)
                    )
            ));
            examples.add(example1);

            Map<String, Object> example2 = new HashMap<>();
            example2.put("description", "三个摄像头拼接，中间摄像头保留中间部分");
            example2.put("config", Map.of(
                    "cameras", List.of(
                            Map.of("index", 0, "x1", 0, "x2", 3800, "y", 0, "h", 3648),
                            Map.of("index", 1, "x1", 1200, "x2", 4200, "y", 0, "h", 3648),
                            Map.of("index", 2, "x1", 1600, "x2", 5472, "y", 0, "h", 3648)
                    )
            ));
            examples.add(example2);

            data.put("examples", examples);

            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get params info", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ================== DTO 类 ==================

    @Schema(description = "设置拼接策略请求")
    public static class SetStrategyRequest {
        @Schema(description = "拼接策略", example = "manual", allowableValues = {"simple", "manual"})
        public String strategy;
    }

    @Schema(description = "手动拼接配置请求（通用版）")
    public static class ManualConfigRequest {
        @Schema(description = "左切割线位置（像素）", example = "0")
        public int x1;

        @Schema(description = "右切割线位置（像素）", example = "5472")
        public int x2;

        @Schema(description = "截取起始 Y 坐标（像素）", example = "0")
        public int y;

        @Schema(description = "截取高度（像素）", example = "3648")
        public int h;
    }

    @Schema(description = "批量手动拼接配置请求")
    public static class BatchManualConfigRequest {
        @Schema(description = "摄像头配置列表")
        public List<ManualConfigRequest> cameras;
    }
}
