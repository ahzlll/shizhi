ALTER TABLE chat_session ADD COLUMN deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN chat_session.deleted_at IS '删除时间。非空表示在回收站里，列表与搜索都不再返回，30 天后由定时任务彻底删除';

CREATE INDEX idx_chat_session_deleted ON chat_session (user_id, deleted_at DESC);
