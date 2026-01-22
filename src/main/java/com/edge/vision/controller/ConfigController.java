package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.quality.MatchStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置管理控制器
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger logger = LoggerFactory.getLogger(ConfigController.class);

    @Autowired
    private YamlConfig yamlConfig;

    /**
     * 获取当前建模方式
     */
    @GetMapping("/match-strategy")
    public ResponseEntity<Map<String, Object>> getMatchStrategy() {
        Map<String, Object> response = new HashMap<>();
        try {
            MatchStrategy strategy = yamlConfig.getInspection().getMatchStrategy();
            if (strategy == null) {
                strategy = MatchStrategy.TOPOLOGY;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("matchStrategy", strategy.toString());
            data.put("description", getStrategyDescription(strategy));

            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to get match strategy", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 设置建模方式
     */
    @PostMapping("/match-strategy")
    public ResponseEntity<Map<String, Object>> setMatchStrategy(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String strategyStr = request.get("matchStrategy");
            if (strategyStr == null) {
                response.put("status", "error");
                response.put("message", "matchStrategy is required");
                return ResponseEntity.badRequest().body(response);
            }

            MatchStrategy strategy = MatchStrategy.valueOf(strategyStr);
            yamlConfig.getInspection().setMatchStrategy(strategy);

            logger.info("Match strategy updated to: {}", strategy);

            Map<String, Object> data = new HashMap<>();
            data.put("matchStrategy", strategy.toString());
            data.put("description", getStrategyDescription(strategy));

            response.put("status", "success");
            response.put("message", "建模方式已更新");
            response.put("data", data);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to set match strategy", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private String getStrategyDescription(MatchStrategy strategy) {
        switch (strategy) {
            case TOPOLOGY:
                return "拓扑图匹配：支持旋转、平移、缩放的全局最优匹配，适合复杂场景";
            case COORDINATE:
                return "坐标直接匹配：一对一关系明确，需要对预先校正坐标";
            case CROP_AREA:
                return "裁剪区域匹配：先检测工件位置并裁剪，然后在裁剪区域中匹配，精度高";
            default:
                return "未知匹配方式";
        }
    }
}
