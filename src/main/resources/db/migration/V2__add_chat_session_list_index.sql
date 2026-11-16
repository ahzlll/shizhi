-- 会话列表按「最后活跃时间倒序、id 兜底」翻页。V1 建表时还没有会话列表这个接口，
-- 这张表只有主键索引，列表查询实际走的是 Seq Scan + Sort。
CREATE INDEX idx_chat_session_user_active
    ON chat_session (user_id, last_active_at DESC, id DESC);
