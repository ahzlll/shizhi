package com.ahz.shizhi.chat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 把 {@link ChatProperties} 挂进容器。单独一个空配置类，比挂到别的配置上更容易找到出处。 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {
}
