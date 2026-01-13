package com.edge.vision.controller;

import com.edge.vision.config.QualityStandardConfig;
import com.edge.vision.service.QualityStandardService;
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

import java.util.*;

/**
 * 质检标准配置控制器
 *
 * 提供质检标准的 CRUD API，配置自动持久化到 data/quality-standards.json
 */
@RestController
@RequestMapping("/api/quality-standards")
@Tag(name = "质检标准配置", description = "质检标准的增删改查管理，支持 >=、<=、== 等比较操作符，配置自动持久化到 data/quality-standards.json")
public class QualityStandardController {
    private static final Logger logger = LoggerFactory.getLogger(QualityStandardController.class);

    @Autowired
    private QualityStandardService qualityStandardService;

    /**
     * 获取所有质检标准配置
     */
    @GetMapping
    @Operation(
            summary = "获取所有质检标准",
            description = """
                    获取所有工件类型的质检标准配置。

                    **配置结构说明**：
                    ```json
                    {
                      "version": "1.0",
                      "description": "Quality inspection standards...",
                      "updatedAt": 1234567890,
                      "standards": {
                        "EKS": [
                          {"defectType": "hole", "operator": "<=", "threshold": 20},
                          {"defectType": "nut", "operator": "==", "threshold": 7}
                        ]
                      }
                    }
                    ```

                    **支持的操作符**：
                    | 操作符 | 说明 | 示例 |
                    |--------|------|------|
                    | == | 数量必须精确等于 | 必需特征计数 |
                    | <= | 数量不超过 | 常规缺陷限制 |
                    | >= | 数量至少达到 | 必需特征（如必须有2个孔）|
                    | < | 数量少于 | 严格限制 |
                    | > | 数量多于 | 至少需要的数量 |
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
                                                "version": "1.0",
                                                "description": "Quality inspection standards...",
                                                "updatedAt": 1704067200000,
                                                "standards": {
                                                  "EKS": [
                                                    {"defectType": "hole", "operator": "<=", "threshold": 20},
                                                    {"defectType": "nut", "operator": "<=", "threshold": 7}
                                                  ]
                                                }
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getAllStandards() {
        Map<String, Object> response = new HashMap<>();
        try {
            QualityStandardConfig config = qualityStandardService.getAllConfig();
            response.put("status", "success");
            response.put("data", config);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get quality standards", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取所有工件类型
     */
    @GetMapping("/part-types")
    @Operation(
            summary = "获取所有工件类型",
            description = "获取所有已配置质检标准的工件类型列表。"
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
                                                "partTypes": ["EKS", "OTHER_TYPE", "TYPE_C"]
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> getPartTypes() {
        Map<String, Object> response = new HashMap<>();
        try {
            Set<String> partTypes = qualityStandardService.getAllPartTypes();
            Map<String, Object> data = new HashMap<>();
            data.put("partTypes", new ArrayList<>(partTypes));
            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get part types", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取指定工件类型的质检标准
     */
    @GetMapping("/part-types/{partType}")
    @Operation(
            summary = "获取指定工件类型的质检标准",
            description = """
                    获取指定工件类型的质检标准配置。

                    **路径参数**：
                    - partType: 工件类型名称（如 EKS）

                    **返回字段说明**：
                    | 字段 | 说明 |
                    |------|------|
                    | defectType | 缺陷类型名称 |
                    | operator | 比较操作符（==、<=、>=、<、>）|
                    | threshold | 阈值 |
                    | description | 标准描述 |
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
                                                "partType": "EKS",
                                                "standards": [
                                                  {
                                                    "defectType": "hole",
                                                    "operator": "<=",
                                                    "threshold": 20,
                                                    "description": "hole 数量不超过 20"
                                                  },
                                                  {
                                                    "defectType": "nut",
                                                    "operator": "<=",
                                                    "threshold": 7,
                                                    "description": "nut 数量不超过 7"
                                                  }
                                                ]
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "工件类型不存在"
            )
    })
    public ResponseEntity<Map<String, Object>> getPartTypeStandards(
            @Parameter(
                    description = "工件类型名称",
                    required = true,
                    example = "EKS"
            )
            @PathVariable String partType) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<QualityStandardConfig.DefectStandard> standards =
                qualityStandardService.getPartTypeStandards(partType);

            Map<String, Object> data = new HashMap<>();
            data.put("partType", partType);
            data.put("standards", standards);
            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get standards for part type: {}", partType, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 更新指定工件类型的质检标准
     */
    @PutMapping("/part-types/{partType}")
    @Operation(
            summary = "更新工件类型的质检标准",
            description = """
                    更新指定工件类型的质检标准。如果工件类型不存在则创建，已存在则覆盖。

                    **操作符说明**：
                    | 操作符 | 含义 | 使用场景 |
                    |--------|------|----------|
                    | == | 精确匹配 | 必需特征必须恰好有N个 |
                    | <= | 不超过 | 缺陷数量上限 |
                    | >= | 至少 | 必需特征至少N个 |
                    | < | 少于 | 严格少于N个 |
                    | > | 多于 | 严格多于N个 |

                    **配置示例**：
                    ```json
                    {
                      "standards": [
                        {"defectType": "hole", "operator": "<=", "threshold": 20},
                        {"defectType": "nut", "operator": "==", "threshold": 7},
                        {"defectType": "scratch", "operator": "<", "threshold": 5}
                      ]
                    }
                    ```

                    **注意**：修改后自动保存到 `data/quality-standards.json`
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "质检标准配置",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = UpdateStandardsRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "standards": [
                                        {"defectType": "hole", "operator": "<=", "threshold": 20},
                                        {"defectType": "nut", "operator": "<=", "threshold": 7}
                                      ]
                                    }
                                    """
                    )
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
                                                "partType": "EKS",
                                                "message": "质检标准已更新",
                                                "standardsCount": 2
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> updatePartTypeStandards(
            @Parameter(
                    description = "工件类型名称",
                    required = true,
                    example = "EKS"
            )
            @PathVariable String partType,
            @RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> standardsList =
                (List<Map<String, Object>>) request.get("standards");

            if (standardsList == null) {
                response.put("status", "error");
                response.put("message", "standards field is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            List<QualityStandardConfig.DefectStandard> standards = new ArrayList<>();
            for (Map<String, Object> item : standardsList) {
                String defectType = (String) item.get("defectType");
                String operator = item.get("operator") != null ? (String) item.get("operator") : "<=";
                int threshold = ((Number) item.get("threshold")).intValue();

                standards.add(new QualityStandardConfig.DefectStandard(defectType, operator, threshold));
            }

            qualityStandardService.updatePartTypeStandards(partType, standards);

            Map<String, Object> data = new HashMap<>();
            data.put("partType", partType);
            data.put("message", "质检标准已更新");
            data.put("standardsCount", standards.size());
            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to update standards for part type: {}", partType, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 删除指定工件类型的质检标准
     */
    @DeleteMapping("/part-types/{partType}")
    @Operation(
            summary = "删除工件类型的质检标准",
            description = """
                    删除指定工件类型的质检标准配置。

                    **注意**：删除后如果检测到该工件类型，将使用默认规则（无缺陷即合格）。
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "删除成功",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "partType": "EKS",
                                                "message": "质检标准已删除"
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> deletePartTypeStandards(
            @Parameter(
                    description = "工件类型名称",
                    required = true,
                    example = "EKS"
            )
            @PathVariable String partType) {
        Map<String, Object> response = new HashMap<>();
        try {
            qualityStandardService.deletePartTypeStandards(partType);

            Map<String, Object> data = new HashMap<>();
            data.put("partType", partType);
            data.put("message", "质检标准已删除");
            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to delete standards for part type: {}", partType, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 重置所有质检标准为默认值
     */
    @PostMapping("/reset")
    @Operation(
            summary = "重置为默认配置",
            description = """
                    将所有质检标准重置为系统默认值。

                    **默认配置**：
                    ```json
                    {
                      "EKS": [
                        {"defectType": "hole", "operator": "<=", "threshold": 20},
                        {"defectType": "nut", "operator": "<=", "threshold": 7}
                      ]
                    }
                    ```

                    **使用场景**：
                    - 配置混乱时重新开始
                    - 测试默认质检效果
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
                                                "message": "质检标准已重置为默认值"
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> resetToDefault() {
        Map<String, Object> response = new HashMap<>();
        try {
            qualityStandardService.resetToDefault();

            Map<String, Object> data = new HashMap<>();
            data.put("message", "质检标准已重置为默认值");
            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to reset quality standards", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 测试质检标准（模拟评估）
     */
    @PostMapping("/test")
    @Operation(
            summary = "测试质检标准",
            description = """
                    模拟评估给定的缺陷数量是否符合质检标准。

                    **请求示例**：
                    ```json
                    {
                      "partType": "EKS",
                      "defects": [
                        {"label": "hole", "count": 5},
                        {"label": "nut", "count": 3}
                      ]
                    }
                    ```

                    **使用场景**：
                    - 配置前测试标准是否合理
                    - 验证配置逻辑是否正确
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "测试缺陷数据",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TestRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "partType": "EKS",
                                      "defects": [
                                        {"label": "hole", "count": 5},
                                        {"label": "nut", "count": 3}
                                      ]
                                    }
                                    """
                    )
            )
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "测试完成",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "status": "success",
                                              "data": {
                                                "partType": "EKS",
                                                "passed": true,
                                                "message": "质检合格",
                                                "details": [
                                                  {
                                                    "defectType": "hole",
                                                    "operator": "<=",
                                                    "threshold": 20,
                                                    "actualCount": 5,
                                                    "passed": true,
                                                    "description": "hole 数量不超过 20"
                                                  }
                                                ]
                                              }
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<Map<String, Object>> testStandards(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String partType = (String) request.get("partType");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> defectsList =
                (List<Map<String, Object>>) request.get("defects");

            if (partType == null || defectsList == null) {
                response.put("status", "error");
                response.put("message", "partType and defects are required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // 获取质检标准
            List<QualityStandardConfig.DefectStandard> standards =
                qualityStandardService.getPartTypeStandards(partType);

            // 统计缺陷数量
            Map<String, Long> defectCounts = new HashMap<>();
            for (Map<String, Object> defect : defectsList) {
                String label = (String) defect.get("label");
                int count = ((Number) defect.get("count")).intValue();
                defectCounts.put(label, defectCounts.getOrDefault(label, 0L) + count);
            }

            // 评估结果
            boolean passed = true;
            List<Map<String, Object>> details = new ArrayList<>();

            for (QualityStandardConfig.DefectStandard standard : standards) {
                String defectType = standard.getDefectType();
                int actualCount = defectCounts.getOrDefault(defectType, 0L).intValue();
                boolean itemPassed = standard.evaluate(actualCount);

                Map<String, Object> detail = new HashMap<>();
                detail.put("defectType", defectType);
                detail.put("operator", standard.getOperator());
                detail.put("threshold", standard.getThreshold());
                detail.put("actualCount", actualCount);
                detail.put("passed", itemPassed);
                detail.put("description", standard.getDescription());
                details.add(detail);

                if (!itemPassed) {
                    passed = false;
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("partType", partType);
            data.put("passed", passed);
            data.put("message", passed ? "质检合格" : "质检不合格");
            data.put("details", details);
            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to test quality standards", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ================== DTO 类 ==================

    @Schema(description = "更新质检标准请求")
    public static class UpdateStandardsRequest {
        @Schema(description = "质检标准列表")
        public List<QualityStandardConfig.DefectStandard> standards;
    }

    @Schema(description = "测试质检标准请求")
    public static class TestRequest {
        @Schema(description = "工件类型", example = "EKS")
        public String partType;

        @Schema(description = "缺陷列表")
        public List<DefectItem> defects;

        @Schema(description = "缺陷项")
        public static class DefectItem {
            @Schema(description = "缺陷标签", example = "hole")
            public String label;

            @Schema(description = "缺陷数量", example = "5")
            public int count;
        }
    }
}
