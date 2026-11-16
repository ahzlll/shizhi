package com.ahz.shizhi.model;

import com.ahz.shizhi.common.api.ErrorCode;

/** 上游 429。对应 5002，前端应该"等一会儿再试"，而不是"立刻重试"。 */
public class ModelRateLimitException extends ModelException {

    public ModelRateLimitException(String message) {
        super(ErrorCode.MODEL_RATE_LIMITED, message);
    }
}
