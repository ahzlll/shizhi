CREATE TABLE user_model_config (
    user_id        BIGINT       PRIMARY KEY REFERENCES sys_user (id) ON DELETE CASCADE,
    base_url       VARCHAR(200) NOT NULL,
    model          VARCHAR(64)  NOT NULL,
    api_key_cipher TEXT         NOT NULL,
    api_key_hint   VARCHAR(8)   NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_model_config IS '用户自带的模型接入配置，一人一条';
COMMENT ON COLUMN user_model_config.api_key_cipher IS 'AES-GCM 加密后的 API key，明文不入库';
COMMENT ON COLUMN user_model_config.api_key_hint IS 'key 的后四位，仅用于界面回显，避免每次读取都解密';
