package com.ahz.shizhi.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahz.shizhi.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HealthControllerTest {

    @Test
    void returnsUpWithActiveProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "shizhi");
        environment.setActiveProfiles("dev");

        ApiResponse<HealthResponse> response = new HealthController(environment, "0.0.1").check();

        assertThat(response.code()).isZero();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data().status()).isEqualTo("UP");
        assertThat(response.data().app()).isEqualTo("shizhi");
        assertThat(response.data().version()).isEqualTo("0.0.1");
        assertThat(response.data().profiles()).containsExactly("dev");
        assertThat(response.data().time()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
    }

    @Test
    void fallsBackToDefaultProfileWhenNoneActive() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "shizhi");

        ApiResponse<HealthResponse> response = new HealthController(environment, "0.0.1").check();

        assertThat(response.data().profiles()).containsExactly("default");
    }
}
