package com.ahz.shizhi.config;

import com.ahz.shizhi.auth.jwt.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class AppConfig {

    /**
     * 只引了 spring-security-crypto，没有引完整的 Spring Security——
     * 要的只是 BCrypt，不想要默认登录页和那一长串过滤器。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
