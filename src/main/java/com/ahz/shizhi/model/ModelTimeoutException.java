package com.ahz.shizhi.model;

import com.ahz.shizhi.common.api.ErrorCode;

/** 首字 / 空闲 / 总时长任一超时。单独一个类型，因为它对应 5003，前端提示是"稍后重试"而不是"服务异常"。 */
public class ModelTimeoutException extends ModelException {

    public ModelTimeoutException(String message) {
        super(ErrorCode.MODEL_TIMEOUT, message);
    }
}
