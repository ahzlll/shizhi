ALTER TABLE chat_message ADD COLUMN model VARCHAR(64);

COMMENT ON COLUMN chat_message.model IS '这一轮实际使用的模型名，回答下方展示用；老数据为空';
