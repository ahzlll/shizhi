package com.ahz.shizhi.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void successHasZeroCodeAndNoErrors() {
        ApiResponse<String> response = ApiResponse.ok("hello");

        assertThat(response.code()).isZero();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data()).isEqualTo("hello");
        assertThat(response.errors()).isNull();
    }

    @Test
    void failureCarriesStructuredFieldErrors() {
        ApiResponse<Void> response = ApiResponse.fail(
                ErrorCode.PARAM_INVALID,
                ErrorCode.PARAM_INVALID.message(),
                List.of(new ParamError("title", "不能为空"), new ParamError("content", "至少 50 字")));

        assertThat(response.code()).isEqualTo(1000);
        assertThat(response.message()).isEqualTo("参数校验失败");
        assertThat(response.data()).isNull();
        assertThat(response.errors()).hasSize(2);
        assertThat(response.errors().get(0).field()).isEqualTo("title");
    }

    @Test
    void emptyErrorListIsTreatedAsAbsent() {
        ApiResponse<Void> response = ApiResponse.fail(ErrorCode.NOTE_NOT_FOUND, null, List.of());

        assertThat(response.errors()).isNull();
        assertThat(response.message()).isEqualTo("笔记不存在或已删除");
    }
}
