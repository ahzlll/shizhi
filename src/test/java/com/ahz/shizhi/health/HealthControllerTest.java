package com.ahz.shizhi.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HealthControllerTest {

    @Test
    void returnsUpWithActiveProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "shizhi");
        environment.setActiveProfiles("dev");

        HealthResponse response = new HealthController(environment, "0.0.1").check();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.app()).isEqualTo("shizhi");
        assertThat(response.version()).isEqualTo("0.0.1");
        assertThat(response.profiles()).containsExactly("dev");
        assertThat(response.time()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
    }

    @Test
    void fallsBackToDefaultProfileWhenNoneActive() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "shizhi");

        HealthResponse response = new HealthController(environment, "0.0.1").check();

        assertThat(response.profiles()).containsExactly("default");
    }
}
