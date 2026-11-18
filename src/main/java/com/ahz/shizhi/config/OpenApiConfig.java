package com.ahz.shizhi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI shizhiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("拾知成图 API")
                        .version("0.0.1")
                        .description("除注册、登录外都需要 accessToken：先调 /api/auth/login 拿 token，"
                                + "点右上角 Authorize 填入即可调试。流式接口返回 text/event-stream，"
                                + "Swagger 里只能看到原始事件流，用 curl 体验更完整。"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
