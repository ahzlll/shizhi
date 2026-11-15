-- 拾知成图 · 初始表结构
-- 13 张表、全部索引与约束。字段语义见本地设计文档 03-数据模型设计.md。

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- 用户与对话
-- ---------------------------------------------------------------------------

CREATE TABLE sys_user (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL,
    email         VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(32),
    status        SMALLINT     NOT NULL DEFAULT 1,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    CONSTRAINT uk_sys_user_email UNIQUE (email)
);
COMMENT ON TABLE sys_user IS '用户';
COMMENT ON COLUMN sys_user.status IS '1 正常 / 0 禁用';
COMMENT ON COLUMN sys_user.password_hash IS 'BCrypt 哈希，不存明文';

CREATE TABLE chat_session (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES sys_user (id),
    title          VARCHAR(120),
    skill_code     VARCHAR(32),
    message_count  INT         NOT NULL DEFAULT 0,
    last_active_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE chat_session IS '会话';
COMMENT ON COLUMN chat_session.message_count IS '消息数冗余计数，含占位行与被替代的行';
COMMENT ON COLUMN chat_session.last_active_at IS '最后活跃时间，用于会话列表排序';

CREATE TABLE chat_message (
    id                BIGSERIAL   PRIMARY KEY,
    session_id        BIGINT      NOT NULL REFERENCES chat_session (id),
    user_id           BIGINT      NOT NULL,
    role              VARCHAR(16) NOT NULL,
    content           TEXT        NOT NULL,
    prompt_tokens     INT,
    completion_tokens INT,
    stream_state      SMALLINT    NOT NULL DEFAULT 1,
    interrupt_reason  VARCHAR(16),
    superseded_by     BIGINT,
    ref_note_ids      BIGINT[],
    ref_concept_ids   BIGINT[],
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT ck_chat_message_role CHECK (role IN ('user', 'assistant')),
    CONSTRAINT ck_chat_message_stream_state CHECK (stream_state BETWEEN 0 AND 3)
);
COMMENT ON TABLE chat_message IS '消息';
COMMENT ON COLUMN chat_message.content IS '正文，允许空串（生成中的占位行），不允许 NULL';
COMMENT ON COLUMN chat_message.stream_state IS '0 生成中 / 1 完成 / 2 中断 / 3 失败';
COMMENT ON COLUMN chat_message.interrupt_reason IS 'CLIENT_CLOSED / MODEL_ERROR / SERVER_TIMEOUT / SHUTDOWN';
COMMENT ON COLUMN chat_message.superseded_by IS '重试成功后指向新的消息 id';
COMMENT ON COLUMN chat_message.updated_at IS '最后一次 flush 的时间，僵尸判定的基准';

-- ---------------------------------------------------------------------------
-- 知识
-- ---------------------------------------------------------------------------

CREATE TABLE kn_note (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    title             VARCHAR(200) NOT NULL,
    category          VARCHAR(64),
    topic             VARCHAR(64),
    summary           TEXT,
    key_points        JSONB,
    examples          JSONB,
    pitfalls          JSONB,
    content_md        TEXT,
    source_message_id BIGINT,
    source_type       SMALLINT     NOT NULL DEFAULT 1,
    importance        SMALLINT     NOT NULL DEFAULT 2,
    skill_code        VARCHAR(32),
    status            SMALLINT     NOT NULL DEFAULT 1,
    merged_into       BIGINT,
    version           INT          NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT ck_kn_note_source_type CHECK (source_type BETWEEN 1 AND 3),
    CONSTRAINT ck_kn_note_importance CHECK (importance BETWEEN 1 AND 3),
    CONSTRAINT ck_kn_note_status CHECK (status BETWEEN 0 AND 2)
);
COMMENT ON TABLE kn_note IS '笔记';
COMMENT ON COLUMN kn_note.key_points IS 'string[]，1~6 条';
COMMENT ON COLUMN kn_note.examples IS 'string[]，0~3 条';
COMMENT ON COLUMN kn_note.pitfalls IS 'string[]，0~3 条';
COMMENT ON COLUMN kn_note.source_type IS '1 问答沉淀 / 2 资料导入 / 3 手动创建';
COMMENT ON COLUMN kn_note.importance IS '1 备用 / 2 过一遍 / 3 重点';
COMMENT ON COLUMN kn_note.status IS '1 正常 / 0 已删除 / 2 已合并或被替代';
COMMENT ON COLUMN kn_note.merged_into IS '被合并进哪条笔记，status=2 时指向主笔记';
COMMENT ON COLUMN kn_note.version IS '编辑正文或改档位都 +1，用于派生任务幂等键';

CREATE TABLE kn_concept (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    name       VARCHAR(120) NOT NULL,
    name_norm  VARCHAR(120) NOT NULL,
    definition TEXT,
    one_liner  VARCHAR(300),
    category   VARCHAR(64),
    origin     SMALLINT     NOT NULL DEFAULT 1,
    note_count INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uk_kn_concept_name UNIQUE (user_id, name_norm),
    CONSTRAINT ck_kn_concept_origin CHECK (origin IN (1, 2))
);
COMMENT ON TABLE kn_concept IS '知识点';
COMMENT ON COLUMN kn_concept.name_norm IS '归一化名：去空格、转小写、去标点';
COMMENT ON COLUMN kn_concept.origin IS '1 模型抽取 / 2 用户手动创建';
COMMENT ON COLUMN kn_concept.note_count IS '冗余计数，按 note_concept 关联重算';

CREATE TABLE kn_note_concept (
    id         BIGSERIAL   PRIMARY KEY,
    note_id    BIGINT      NOT NULL REFERENCES kn_note (id),
    concept_id BIGINT      NOT NULL REFERENCES kn_concept (id),
    created_at TIMESTAMPTZ,
    CONSTRAINT uk_kn_note_concept UNIQUE (note_id, concept_id)
);
COMMENT ON TABLE kn_note_concept IS '笔记与知识点关联';

CREATE TABLE kn_concept_edge (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    source_id  BIGINT       NOT NULL REFERENCES kn_concept (id),
    target_id  BIGINT       NOT NULL REFERENCES kn_concept (id),
    weight     NUMERIC(4,3),
    explain    VARCHAR(500),
    origin     SMALLINT     NOT NULL DEFAULT 1,
    status     SMALLINT     NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ,
    CONSTRAINT uk_kn_concept_edge UNIQUE (user_id, source_id, target_id),
    CONSTRAINT ck_kn_concept_edge_order CHECK (source_id < target_id),
    CONSTRAINT ck_kn_concept_edge_no_self CHECK (source_id <> target_id),
    CONSTRAINT ck_kn_concept_edge_origin CHECK (origin IN (1, 2)),
    CONSTRAINT ck_kn_concept_edge_status CHECK (status IN (0, 1))
);
COMMENT ON TABLE kn_concept_edge IS '知识点连线';
COMMENT ON COLUMN kn_concept_edge.weight IS '语义相似度 0~1，只有自动边有值，人工边为 NULL';
COMMENT ON COLUMN kn_concept_edge.explain IS '为什么相关，人工边必填';
COMMENT ON COLUMN kn_concept_edge.origin IS '1 自动生成 / 2 人工建立';
COMMENT ON COLUMN kn_concept_edge.status IS '1 有效 / 0 用户隐藏（墓碑，自动重建时跳过）';

CREATE TABLE kn_note_merge (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    new_note_id       BIGINT       NOT NULL REFERENCES kn_note (id),
    existing_note_id  BIGINT       NOT NULL REFERENCES kn_note (id),
    similarity        NUMERIC(4,3) NOT NULL,
    status            SMALLINT     NOT NULL DEFAULT 0,
    merged_note_id    BIGINT,
    payload           JSONB,
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_kn_note_merge UNIQUE (user_id, new_note_id, existing_note_id),
    CONSTRAINT ck_kn_note_merge_distinct CHECK (new_note_id <> existing_note_id),
    CONSTRAINT ck_kn_note_merge_status CHECK (status BETWEEN 0 AND 4)
);
COMMENT ON TABLE kn_note_merge IS '待确认的重复笔记';
COMMENT ON COLUMN kn_note_merge.status IS '0 待确认 / 1 已合并 / 2 都保留 / 3 用新的替换 / 4 超时忽略';
COMMENT ON COLUMN kn_note_merge.payload IS '{oldContentMd, newContentMd, mergedContentMd, capturedAt}，用于回滚';

CREATE TABLE kn_chunk (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    note_id     BIGINT       NOT NULL REFERENCES kn_note (id),
    seq         INT          NOT NULL,
    content     TEXT         NOT NULL,
    token_count INT,
    embedding   VECTOR(512),
    created_at  TIMESTAMPTZ,
    CONSTRAINT uk_kn_chunk_seq UNIQUE (note_id, seq)
);
COMMENT ON TABLE kn_chunk IS '文本切片，检索单位';
COMMENT ON COLUMN kn_chunk.embedding IS '向量维度与 bge-small-zh 对齐';

-- ---------------------------------------------------------------------------
-- 复习
-- ---------------------------------------------------------------------------

CREATE TABLE rv_card (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    note_id        BIGINT      REFERENCES kn_note (id),
    concept_id     BIGINT      REFERENCES kn_concept (id),
    state          SMALLINT    NOT NULL DEFAULT 1,
    difficulty     REAL        NOT NULL DEFAULT 5.0,
    stability      REAL        NOT NULL DEFAULT 0,
    reps           INT         NOT NULL DEFAULT 0,
    lapses         INT         NOT NULL DEFAULT 0,
    last_review_at TIMESTAMPTZ,
    due_date       DATE        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    CONSTRAINT ck_rv_card_target CHECK ((note_id IS NULL) <> (concept_id IS NULL)),
    CONSTRAINT ck_rv_card_state CHECK (state IN (1, 2, 3))
);
COMMENT ON TABLE rv_card IS '复习卡';
COMMENT ON COLUMN rv_card.state IS '1 学习中 / 2 复习中 / 3 暂停';
COMMENT ON COLUMN rv_card.difficulty IS 'FSRS 的 D，范围 1~10';
COMMENT ON COLUMN rv_card.stability IS 'FSRS 的 S，单位天';
COMMENT ON COLUMN rv_card.due_date IS '下次到期日，每日扫描的依据';

CREATE TABLE rv_log (
    id             BIGSERIAL   PRIMARY KEY,
    card_id        BIGINT      NOT NULL REFERENCES rv_card (id),
    user_id        BIGINT      NOT NULL,
    rating         SMALLINT    NOT NULL,
    prev_due_date  DATE,
    next_due_date  DATE,
    prev_stability REAL,
    next_stability REAL,
    elapsed_ms     INT,
    reviewed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rv_log_rating CHECK (rating BETWEEN 1 AND 4)
);
COMMENT ON TABLE rv_log IS '复习记录，同时存前后值便于回放调度决策';
COMMENT ON COLUMN rv_log.rating IS '1 忘了 / 2 模糊 / 3 记得 / 4 很简单';

-- ---------------------------------------------------------------------------
-- 运维
-- ---------------------------------------------------------------------------

CREATE TABLE ops_task (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT,
    type        VARCHAR(32)  NOT NULL,
    payload     JSONB        NOT NULL,
    status      SMALLINT     NOT NULL DEFAULT 0,
    retry_count INT          NOT NULL DEFAULT 0,
    max_retry   INT          NOT NULL DEFAULT 3,
    idem_key    VARCHAR(128) NOT NULL,
    next_run_at TIMESTAMPTZ  NOT NULL,
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT uk_ops_task_idem UNIQUE (idem_key),
    CONSTRAINT ck_ops_task_status CHECK (status BETWEEN 0 AND 4),
    CONSTRAINT ck_ops_task_type CHECK (type IN ('NOTE_BUILD', 'NOTE_INDEX', 'EDGE_REBUILD', 'CARD_REBUILD', 'REVIEW_NOTIFY'))
);
COMMENT ON TABLE ops_task IS '异步任务';
COMMENT ON COLUMN ops_task.status IS '0 待处理 / 1 处理中 / 2 成功 / 3 失败 / 4 跳过';
COMMENT ON COLUMN ops_task.idem_key IS '幂等键，同一逻辑任务只能有一条';
COMMENT ON COLUMN ops_task.next_run_at IS '下次尝试时间，按 retry_count 指数退避';

CREATE TABLE ops_usage (
    id                BIGSERIAL   PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    scene             VARCHAR(32) NOT NULL,
    model             VARCHAR(64),
    prompt_tokens     INT,
    completion_tokens INT,
    cost_micro        BIGINT      NOT NULL DEFAULT 0,
    latency_ms        INT,
    first_token_ms    INT,
    finish_reason     VARCHAR(16),
    usage_source      SMALLINT    NOT NULL DEFAULT 1,
    success           BOOLEAN     NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE ops_usage IS '模型调用用量与成本';
COMMENT ON COLUMN ops_usage.scene IS 'CHAT / NOTE_BUILD / EDGE / CLASSIFY / EMBED / MERGE_HINT';
COMMENT ON COLUMN ops_usage.cost_micro IS '花费，单位 1e-6 元';
COMMENT ON COLUMN ops_usage.latency_ms IS '端到端耗时，不是首字延迟';
COMMENT ON COLUMN ops_usage.first_token_ms IS '首字延迟，流式开始到第一个 delta';
COMMENT ON COLUMN ops_usage.usage_source IS '1 供应商返回 / 2 本地估算';

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------

CREATE INDEX idx_chat_message_session ON chat_message (session_id, id);
CREATE INDEX idx_chat_message_user_time ON chat_message (user_id, created_at DESC);
CREATE INDEX idx_chat_message_zombie ON chat_message (stream_state, updated_at) WHERE stream_state = 0;

CREATE INDEX idx_kn_note_list ON kn_note (user_id, status, updated_at DESC);
CREATE INDEX idx_kn_note_category ON kn_note (user_id, category, updated_at DESC);
CREATE INDEX idx_kn_note_fts ON kn_note USING GIN (to_tsvector('simple', content_md));

CREATE INDEX idx_kn_note_concept_concept ON kn_note_concept (concept_id);
CREATE INDEX idx_kn_concept_edge_source ON kn_concept_edge (user_id, source_id);
CREATE INDEX idx_kn_concept_edge_target ON kn_concept_edge (user_id, target_id);
CREATE INDEX idx_kn_note_merge_pending ON kn_note_merge (user_id, status, created_at DESC);

CREATE INDEX idx_kn_chunk_note ON kn_chunk (note_id);
CREATE INDEX idx_kn_chunk_embedding ON kn_chunk
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

CREATE INDEX idx_rv_card_due ON rv_card (user_id, due_date, state);
CREATE UNIQUE INDEX uk_rv_card_note ON rv_card (user_id, note_id) WHERE note_id IS NOT NULL;
CREATE UNIQUE INDEX uk_rv_card_concept ON rv_card (user_id, concept_id) WHERE concept_id IS NOT NULL;

CREATE INDEX idx_ops_task_poll ON ops_task (status, next_run_at);
CREATE INDEX idx_ops_usage_user_time ON ops_usage (user_id, created_at);
