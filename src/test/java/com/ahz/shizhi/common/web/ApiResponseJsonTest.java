package com.ahz.shizhi.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahz.shizhi.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用一个临时控制器跑真实的 HTTP 链路，确认参数校验失败后的 JSON 结构
 * 和文档里写的完全一致（含 errors 数组与 traceId）。
 */
class ApiResponseJsonTest {

    record DemoPayload(@NotBlank String title, @NotBlank String content) {
    }

    @RestController
    static class DemoController {
        @PostMapping("/demo")
        ApiResponse<Void> create(@Valid @RequestBody DemoPayload payload) {
            return ApiResponse.ok();
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DemoController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

    @Test
    void invalidBodyReturnsStructuredFieldErrors() throws Exception {
        mockMvc.perform(post("/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItems("title", "content")))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void validBodyReturnsSuccessWithoutErrorsField() throws Exception {
        mockMvc.perform(post("/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
