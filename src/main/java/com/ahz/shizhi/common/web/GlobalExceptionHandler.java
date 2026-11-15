package com.ahz.shizhi.common.web;

import java.util.ArrayList;
import java.util.List;

import com.ahz.shizhi.common.api.ApiResponse;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.api.ParamError;
import com.ahz.shizhi.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口。所有失败都收敛成统一响应体，并且带上 traceId，
 * 这样用户截图报错时能直接拿 traceId 去日志里定位。
 *
 * <p>参数类错误一律返回字段级结构（{@code errors}），而不是拼成一句话——
 * 前端靠 {@code field} 把提示显示到对应输入框旁边。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode errorCode = ex.errorCode();
        if (errorCode.status().is5xxServerError()) {
            log.error("业务异常 code={}", errorCode.code(), ex);
        } else {
            log.warn("业务异常 code={} message={}", errorCode.code(), ex.getMessage());
        }
        return build(errorCode, ex.getMessage());
    }

    /** 请求体 @Valid 校验失败。字段错误与整体错误都收进来。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidBody(MethodArgumentNotValidException ex) {
        List<ParamError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.add(new ParamError(error.getField(), error.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.add(new ParamError(null, error.getDefaultMessage())));
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.message(), errors);
    }

    /** 方法参数（@RequestParam、@PathVariable）校验失败。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidParameter(HandlerMethodValidationException ex) {
        List<ParamError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ParamError(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.message(), errors);
    }

    /** 路径既没有映射也没有静态资源。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, "接口不存在：/" + ex.getResourcePath());
    }

    /** 路径存在但方法不对，比如拿 POST 打 GET 接口。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, "该接口不支持 " + ex.getMethod());
    }

    /** Content-Type 不对，比如该发 JSON 却发了表单。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "不支持的 Content-Type：" + ex.getContentType());
    }

    /** 缺少必填的查询参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        List<ParamError> errors = List.of(new ParamError(ex.getParameterName(), "不能为空"));
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.message(), errors);
    }

    /** 参数类型对不上，比如 id 传了字符串。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        List<ParamError> errors = List.of(new ParamError(ex.getName(), "类型不正确"));
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.message(), errors);
    }

    /** 兜底。堆栈只进日志，不进响应体。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未捕获异常", ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode errorCode, String message) {
        return build(errorCode, message, null);
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode errorCode, String message, List<ParamError> errors) {
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.fail(errorCode, message, errors));
    }
}
