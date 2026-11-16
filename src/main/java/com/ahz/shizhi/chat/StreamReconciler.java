package com.ahz.shizhi.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把停在「生成中」的消息收敛成「中断」。
 *
 * <p>为什么需要三层而不是一层：进程重启时只有启动扫描能覆盖；进程活着但上游连接半开时只有定时扫描能覆盖；
 * 前两层都是后台猜，而用户马上要看到历史时只有读时收敛能给出确定答案。少任何一层都有洞——
 * 只做读时收敛，用户不打开那个会话就永远没人收拾它。
 */
@Component
public class StreamReconciler {

    private static final Logger log = LoggerFactory.getLogger(StreamReconciler.class);

    private final MessageRepository messages;

    public StreamReconciler(MessageRepository messages) {
        this.messages = messages;
    }

    /**
     * 启动扫描。不加时间条件：进程刚起来，本机不可能还有活着的流，凡是停在这一状态的都是上一次运行留下的。
     *
     * <p>这个前提是单实例部署。多实例时启动会误杀另一台正在生成的回答，那时需要改成按实例标记。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        report(messages.reconcileAllGenerating(), "启动");
    }

    /**
     * 定时兜底。只碰超过阈值的行：阈值远大于一次 flush 周期，正在吐字的流不会被误杀，
     * 而挂死的流最多等一个扫描周期就会被改成「中断」。
     *
     * <p>间隔走配置而不是写死：默认 5 分钟，测试里可以压到毫秒级来证明调度器真的被注册。
     */
    @Scheduled(fixedDelayString = "${shizhi.chat.reconcile-interval:5m}")
    public void reconcileStale() {
        report(messages.reconcileStale(), "定时");
    }

    private void report(int rows, String trigger) {
        if (rows > 0) {
            log.warn("{}收敛僵尸消息 rows={}", trigger, rows);
        }
    }
}
