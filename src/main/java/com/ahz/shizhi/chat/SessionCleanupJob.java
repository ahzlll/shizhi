package com.ahz.shizhi.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站过期清理。
 *
 * <p>每天凌晨跑一次，把删除超过 30 天的会话连同消息彻底删掉。放凌晨是因为它可能删很多行，
 * 白天跑会和正常请求抢行锁。
 */
@Component
public class SessionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupJob.class);

    /** 保留期，和前端回收站页上的说明保持一致。 */
    static final int RETENTION_DAYS = 30;

    private final SessionService sessionService;

    public SessionCleanupJob(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void purgeExpiredSessions() {
        try {
            int removed = sessionService.purgeExpired(RETENTION_DAYS);
            if (removed > 0) {
                log.info("清理回收站过期会话 {} 条", removed);
            }
        } catch (RuntimeException ex) {
            // 清理失败不影响任何请求，下一轮再试
            log.warn("清理回收站失败", ex);
        }
    }
}
