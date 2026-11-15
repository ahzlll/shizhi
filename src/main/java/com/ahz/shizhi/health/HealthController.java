package com.ahz.shizhi.health;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import com.ahz.shizhi.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final Environment environment;
    private final String version;

    public HealthController(Environment environment,
                            @Value("${shizhi.version:unknown}") String version) {
        this.environment = environment;
        this.version = version;
    }

    @GetMapping
    public ApiResponse<HealthResponse> check() {
        String[] activeProfiles = environment.getActiveProfiles();
        List<String> profiles = activeProfiles.length == 0
                ? List.of("default")
                : Arrays.asList(activeProfiles);

        return ApiResponse.ok(new HealthResponse(
                "UP",
                environment.getProperty("spring.application.name", "shizhi"),
                version,
                profiles,
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ));
    }
}
