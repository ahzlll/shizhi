ALTER TABLE sys_user ADD COLUMN avatar_data BYTEA;
ALTER TABLE sys_user ADD COLUMN avatar_type VARCHAR(32);
ALTER TABLE sys_user ADD COLUMN avatar_updated_at TIMESTAMPTZ;

COMMENT ON COLUMN sys_user.avatar_data IS '头像原图，限制 2MB；没有单独的对象存储，随库备份';
COMMENT ON COLUMN sys_user.avatar_updated_at IS '头像最后更新时间，前端用它做缓存版本号';

CREATE TABLE user_feedback (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES sys_user (id),
    content    TEXT         NOT NULL,
    contact    VARCHAR(128),
    page       VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_feedback IS '用户反馈，暂时只落库，不做管理页';
