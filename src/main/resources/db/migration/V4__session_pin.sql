ALTER TABLE chat_session ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN chat_session.pinned IS '置顶。列表排序时置顶的排在最前，不改变最后活跃时间';

CREATE INDEX idx_chat_session_pinned ON chat_session (user_id, pinned DESC, last_active_at DESC, id DESC);
