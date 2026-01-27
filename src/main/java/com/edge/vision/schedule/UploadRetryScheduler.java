package com.edge.vision.schedule;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.model.InspectionEntity;
import com.edge.vision.repository.InspectionRepository;
import com.edge.vision.service.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据重传定时任务
 * 每天晚上12点执行，重传当天未上传的数据
 */
@Component
public class UploadRetryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(UploadRetryScheduler.class);

    @Autowired
    private InspectionRepository repository;

    @Autowired
    private YamlConfig config;

    @Autowired
    private DataManager dataManager;

    /**
     * 01:00 执行重传任务
     * cron表达式: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 1 * * ?")   // 01:00 执行
    public void retryUploadFailedRecords() {
        // 检查远程地址是否配置
        if (!StringUtils.hasText(config.getRemote().getUploadUrl())) {
            logger.debug("Remote upload URL not configured, skip retry task");
            return;
        }
        LocalDate localDate = LocalDate.now().minusDays(1);
        logger.info("Starting retry upload task for date: {}", localDate);

        // 查询当天未上传的记录
        List<InspectionEntity> unuploadedRecords = repository.findUnuploadedByDate(localDate);

        if (unuploadedRecords.isEmpty()) {
            logger.info("No unuploaded records found for date: {}", localDate);
            return;
        }

        logger.info("Found {} unuploaded records", unuploadedRecords.size());

        int successCount = 0;
        int failCount = 0;

        for (InspectionEntity entity : unuploadedRecords) {
            try {
                // 使用 DataManager 的上传方法
                dataManager.asyncUpload(entity);

                // 更新上传状态
                entity.setUploaded(true);
                repository.update(entity);

                successCount++;
                logger.info("Successfully re-uploaded record: {}", entity.getId());

            } catch (Exception e) {
                failCount++;
                logger.error("Failed to re-upload record: {}", entity.getId(), e);
            }
        }

        logger.info("Retry upload task completed. Success: {}, Failed: {}", successCount, failCount);
    }
}
