package com.ahz.shizhi.chat;

import jakarta.annotation.PostConstruct;

import com.ahz.shizhi.model.ModelProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 聊天模块的配置入口：绑定参数、开定时任务、启动时校验两个超时参数的关系。 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
@EnableScheduling
public class ChatConfig {

    /**
     * 收敛阈值要比模型总超时多留这么多秒，用来覆盖「最后一次 flush 之后到收尾之间」的那段尾巴。
     */
    private static final long TIME_MARGIN_SECONDS = 30L;

    private final ModelProperties modelProperties;

    public ChatConfig(ModelProperties modelProperties) {
        this.modelProperties = modelProperties;
    }

    /**
     * 收敛阈值必须严格大于模型总超时加余量，否则一个正常生成、只是吐字慢的回答会被当成僵尸改成「中断」，
     * 等它真跑完又写回「完成」——用户看到状态翻转一次，且没人解释得清。
     *
     * <p>宁可起不来：这两个值一个在配置里、一个是代码常量，配错了没有任何运行期信号。
     */
    @PostConstruct
    void verifyReconcileThreshold() {
        long totalTimeoutSeconds = modelProperties.totalTimeout().toSeconds();
        if (totalTimeoutSeconds + TIME_MARGIN_SECONDS > MessageRepository.STALE_AFTER_SECONDS) {
            throw new IllegalStateException(
                    "shizhi.model.total-timeout=" + totalTimeoutSeconds + "s 加上 " + TIME_MARGIN_SECONDS + "s 余量后"
                            + "仍超过消息收敛阈值 MessageRepository.STALE_AFTER_SECONDS="
                            + MessageRepository.STALE_AFTER_SECONDS + "s："
                            + "正在缓慢生成的回答会被误判为中断，请调小 total-timeout 或调大收敛阈值");
        }
    }
}
