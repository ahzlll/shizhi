package com.ahz.shizhi.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahz.shizhi.common.api.ApiResponse;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.api.ParamError;
import com.ahz.shizhi.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionKeepsItsOwnStatusAndCode() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.NOTE_NOT_FOUND));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(2005);
    }

    @Test
    void businessExceptionCanOverrideMessage() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.PARAM_INVALID, "title 不能为空"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("title 不能为空");
    }

    @Test
    void unexpectedExceptionFallsBackToInternalError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpected(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.code());
        assertThat(response.getBody().message()).isEqualTo("服务内部错误");
    }

    @Test
    void serviceErrorCodeMapsToGatewayStatus() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.MODEL_TIMEOUT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody().code()).isEqualTo(5003);
    }

    @Test
    void missingParameterBecomesFieldLevelError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMissingParameter(new MissingServletRequestParameterException("title", "String"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(response.getBody().message()).isEqualTo("参数校验失败");
        assertThat(response.getBody().errors())
                .containsExactly(new ParamError("title", "不能为空"));
    }

    @Test
    void businessErrorCarriesNoFieldErrors() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.NOTE_NOT_FOUND));

        assertThat(response.getBody().errors()).isNull();
    }
}
