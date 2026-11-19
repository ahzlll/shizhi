package com.ahz.shizhi.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 会话与历史消息的端到端测试：真起一个 HTTP 服务，消息数据用 JdbcTemplate 直接插
 * （本轮没有写发消息的接口）。
 *
 * <p>每个用例都新建独立用户，靠用户隔离保证断言与库里已有数据无关——测试库不会每次清空。
 *
 * <p>端口随机：写死端口时两个 worker 并行跑会互抢，整类 0.001s 级全红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChatFlowIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    /** 会话列表里每个会话对象都以 id 开头，title 紧跟其后。 */
    private static final Pattern SESSION_IDS = Pattern.compile("\"id\":(\\d+),\"title\":");
    /** 消息对象以 id 开头、role 紧跟其后，按响应里的出现顺序收集就是 list 的顺序。 */
    private static final Pattern MESSAGE_IDS = Pattern.compile("\\{\"id\":(\\d+),\"role\":\"");
    /** 时间要带时区：只到秒的裸时间戳，前端没法判断是哪个时区。 */
    private static final String ISO_TIME_BODY =
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})";

    @Autowired
    private JdbcTemplate jdbc;

    private record Resp(int status, String body) {
    }

    private record Account(String username, String token, long userId) {
    }

    @Test
    void createSessionWithoutBodyLeavesTitleEmpty() throws Exception {
        Account account = newUser("cs");

        Resp withoutBody = post("/api/sessions", null, account.token());
        assertThat(withoutBody.status()).isEqualTo(200);
        assertThat(withoutBody.body())
                .contains("\"code\":0")
                .contains("\"title\":null")
                .contains("\"skillCode\":null")
                .contains("\"messageCount\":0");
        assertHasIsoTime(withoutBody.body(), "lastActiveAt");
        assertHasIsoTime(withoutBody.body(), "createdAt");
        assertThat(sessionIds(withoutBody.body())).hasSize(1);

        Resp withSkill = post("/api/sessions", """
                {"skillCode":"java"}
                """, account.token());
        assertThat(withSkill.status()).isEqualTo(200);
        assertThat(withSkill.body())
                .contains("\"title\":null")
                .contains("\"skillCode\":\"java\"");
    }

    @Test
    void tooLongSkillCodeReturnsFieldLevelError() throws Exception {
        Account account = newUser("sk");
        String tooLong = "x".repeat(33);

        Resp resp = post("/api/sessions", """
                {"skillCode":"%s"}
                """.formatted(tooLong), account.token());

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body())
                .contains("\"code\":1000")
                .contains("\"field\":\"skillCode\"");
    }

    @Test
    void malformedJsonBodyIsClientErrorNotServerError() throws Exception {
        Account account = newUser("mj");

        Resp resp = post("/api/sessions", "{\"skillCode\":", account.token());

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body())
                .contains("\"code\":1000")
                // 解析阶段就失败了，挂不到具体字段，不能带 errors
                .doesNotContain("\"errors\"");
    }

    /**
     * 会话列表的排序和分页完全依赖这条复合索引，没有它 PostgreSQL 会退化成 Seq Scan + Sort。
     * 索引列顺序必须和 {@code pageByUser} 的 WHERE + ORDER BY 一致，写单测难以发现漂移，
     * 查一次系统表最直接：哪天有人手滑删了索引，这条会先红。
     */
    @Test
    void sessionListIndexExists() {
        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'chat_session' AND indexname = 'idx_chat_session_user_active'
                """, String.class);

        assertThat(indexes).hasSize(1);
    }

    @Test
    void sessionListIsNewestFirstAndIsolatedPerUser() throws Exception {
        Account owner = newUser("ls");
        long first = createSession(owner.token(), "{\"skillCode\":\"java\"}");
        long second = createSession(owner.token(), "{\"skillCode\":\"sql\"}");

        Resp listed = get("/api/sessions", owner.token());
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).contains("\"total\":2").contains("\"page\":1").contains("\"size\":20");
        assertThat(sessionIds(listed.body())).containsExactly(second, first);

        Account stranger = newUser("ot");
        Resp strangerList = get("/api/sessions", stranger.token());
        assertThat(strangerList.status()).isEqualTo(200);
        assertThat(strangerList.body()).contains("\"total\":0").contains("\"list\":[]");
    }

    @Test
    void invalidPaginationReturnsFieldLevelErrors() throws Exception {
        Account account = newUser("pg");

        Resp smallPage = get("/api/sessions?page=0", account.token());
        assertThat(smallPage.status()).isEqualTo(400);
        assertThat(smallPage.body()).contains("\"code\":1000").contains("\"field\":\"page\"");

        Resp largeSize = get("/api/sessions?size=101", account.token());
        assertThat(largeSize.status()).isEqualTo(400);
        assertThat(largeSize.body()).contains("\"code\":1000").contains("\"field\":\"size\"");

        Resp badMessageSize = get("/api/sessions/1/messages?size=101", account.token());
        assertThat(badMessageSize.status()).isEqualTo(400);
        assertThat(badMessageSize.body()).contains("\"code\":1000").contains("\"field\":\"size\"");
    }

    @Test
    void historyWalksBackwardsInAscendingOrder() throws Exception {
        Account account = newUser("cu");
        long sessionId = createSession(account.token(), null);

        List<Long> inserted = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            inserted.add(insertMessage(sessionId, account.userId(),
                    i % 2 == 0 ? "user" : "assistant", "message-" + i));
        }

        Resp firstPage = get("/api/sessions/" + sessionId + "/messages?size=10", account.token());
        assertThat(firstPage.status()).isEqualTo(200);
        List<Long> firstIds = messageIds(firstPage.body());
        assertThat(firstIds).hasSize(10).isSorted();
        assertThat(firstIds).containsExactlyElementsOf(inserted.subList(15, 25));
        assertThat(firstPage.body()).contains("\"hasMore\":true");
        long cursor = nextCursor(firstPage.body());
        assertThat(cursor).isEqualTo(inserted.get(15));

        Resp secondPage = get("/api/sessions/" + sessionId + "/messages?size=10&cursor=" + cursor,
                account.token());
        List<Long> secondIds = messageIds(secondPage.body());
        assertThat(secondIds).hasSize(10).isSorted();
        assertThat(secondIds).containsExactlyElementsOf(inserted.subList(5, 15));
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
        assertThat(secondPage.body()).contains("\"hasMore\":true");

        Resp thirdPage = get("/api/sessions/" + sessionId + "/messages?size=10&cursor="
                + nextCursor(secondPage.body()), account.token());
        List<Long> thirdIds = messageIds(thirdPage.body());
        assertThat(thirdIds).hasSize(5).isSorted();
        assertThat(thirdIds).containsExactlyElementsOf(inserted.subList(0, 5));
        assertThat(thirdPage.body()).contains("\"hasMore\":false").contains("\"nextCursor\":null");
    }

    @Test
    void supersededMessagesAreHidden() throws Exception {
        Account account = newUser("sp");
        long sessionId = createSession(account.token(), null);

        long kept = insertMessage(sessionId, account.userId(), "user", "kept");
        long replaced = insertMessage(sessionId, account.userId(), "assistant", "old answer");
        long retried = insertMessage(sessionId, account.userId(), "assistant", "new answer");
        jdbc.update("UPDATE chat_message SET superseded_by = ? WHERE id = ?", retried, replaced);

        Resp resp = get("/api/sessions/" + sessionId + "/messages", account.token());

        assertThat(messageIds(resp.body())).containsExactly(kept, retried);
    }

    @Test
    void interruptedMessageCarriesStateReasonAndReferences() throws Exception {
        Account account = newUser("it");
        long sessionId = createSession(account.token(), null);

        jdbc.update("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state,
                        interrupt_reason, ref_note_ids, ref_concept_ids, created_at, updated_at)
                VALUES (?, ?, 'assistant', 'half an answer', 2, 'CLIENT_CLOSED',
                        ?::bigint[], ?::bigint[], now(), now())
                """, sessionId, account.userId(), "{3,7}", "{11}");

        Resp resp = get("/api/sessions/" + sessionId + "/messages", account.token());

        assertThat(resp.body())
                .contains("\"streamState\":2")
                .contains("\"interruptReason\":\"CLIENT_CLOSED\"")
                .contains("\"supersededBy\":null")
                .contains("\"refNoteIds\":[3,7]")
                .contains("\"refConceptIds\":[11]");
        assertHasIsoTime(resp.body(), "createdAt");
    }

    @Test
    void historyRejectsForeignMissingAndAnonymousCallers() throws Exception {
        Account owner = newUser("ac");
        long sessionId = createSession(owner.token(), null);
        insertMessage(sessionId, owner.userId(), "user", "hello");

        Account stranger = newUser("ex");
        Resp foreign = get("/api/sessions/" + sessionId + "/messages", stranger.token());
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.body()).contains("\"code\":1004");

        Resp missing = get("/api/sessions/999999999/messages", owner.token());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":2004");

        Resp anonymous = get("/api/sessions/" + sessionId + "/messages", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body()).contains("\"code\":1002");
    }

    @Test
    void staleGeneratingMessageConvergesToInterruptedOnFirstPage() throws Exception {
        Account account = newUser("zo");
        long sessionId = createSession(account.token(), null);

        Long stale = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, created_at, updated_at)
                VALUES (?, ?, 'assistant', '', 0, now() - interval '30 minutes', now() - interval '30 minutes')
                RETURNING id
                """, Long.class, sessionId, account.userId());
        Long fresh = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, created_at, updated_at)
                VALUES (?, ?, 'assistant', 'still streaming', 0, now(), now())
                RETURNING id
                """, Long.class, sessionId, account.userId());

        Resp resp = get("/api/sessions/" + sessionId + "/messages", account.token());
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body())
                .contains("\"id\":" + stale + ",\"role\":\"assistant\"")
                .contains("\"streamState\":2")
                .contains("\"interruptReason\":\"SHUTDOWN\"");

        assertThat(streamStateOf(stale)).isEqualTo(2);
        assertThat(interruptReasonOf(stale)).isEqualTo("SHUTDOWN");
        // 正在生成的行不能被误杀，否则用户刷新一次就看到「已中断」
        assertThat(streamStateOf(fresh)).isZero();
        assertThat(interruptReasonOf(fresh)).isNull();
    }

    /**
     * 消息条数刚好等于 size：多取一条判断下一页的逻辑最容易在这里写成「大于等于」。
     * 写错的话 hasMore 恒为 true、nextCursor 指向本页最早一条，前端「加载更多」会用同一个
     * 游标反复问同一个空页，表现就是滚到底部无限转圈。
     */
    @Test
    void pageWithExactlySizeMessagesReportsNoMore() throws Exception {
        Account account = newUser("eq");
        long sessionId = createSession(account.token(), null);
        List<Long> inserted = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            inserted.add(insertMessage(sessionId, account.userId(),
                    i % 2 == 0 ? "user" : "assistant", "msg-" + i));
        }

        Resp exact = get("/api/sessions/" + sessionId + "/messages?size=20", account.token());
        assertThat(exact.status()).isEqualTo(200);
        assertThat(messageIds(exact.body())).hasSize(20).containsExactlyElementsOf(inserted);
        assertThat(exact.body()).contains("\"hasMore\":false").contains("\"nextCursor\":null");

        // 对照组：多一条就必须翻页，防止上面那条断言只是因为「永远判 false」才通过
        inserted.add(insertMessage(sessionId, account.userId(), "user", "msg-20"));
        Resp over = get("/api/sessions/" + sessionId + "/messages?size=20", account.token());
        assertThat(over.body()).contains("\"hasMore\":true");
        // 本页最早一条是插入的第 2 条（下标 1），游标必须指向它而不是最新一条
        assertThat(nextCursor(over.body())).isEqualTo(inserted.get(1));
    }

    /**
     * cursor 传 0 或负数时 SQL 是 {@code id < ?}，结果必然为空——这里要确认的是**不 500**。
     * 把用户传的游标直接拼进 SQL、或当成数组下标，都会在这一步炸掉。
     * 顺带测「cursor 不是数字」：那是 Spring 的类型转换失败，应该是 400 + 字段级错误。
     */
    @Test
    void nonPositiveCursorYieldsEmptyPageInsteadOfError() throws Exception {
        Account account = newUser("c0");
        long sessionId = createSession(account.token(), null);
        insertMessage(sessionId, account.userId(), "user", "only one");

        for (String cursor : List.of("0", "-1", "-9999")) {
            Resp resp = get("/api/sessions/" + sessionId + "/messages?cursor=" + cursor, account.token());
            assertThat(resp.status()).as("cursor=%s 不该 500", cursor).isEqualTo(200);
            assertThat(resp.body())
                    .as("cursor=%s 应该是一页空数据而不是报错", cursor)
                    .contains("\"list\":[]")
                    .contains("\"hasMore\":false")
                    .contains("\"nextCursor\":null");
        }

        Resp notANumber = get("/api/sessions/" + sessionId + "/messages?cursor=abc", account.token());
        assertThat(notANumber.status()).isEqualTo(400);
        assertThat(notANumber.body()).contains("\"code\":1000").contains("\"cursor\"");
    }

    /**
     * page 超出总页数：空 list + 真实 total，不能报错。
     * total 是前端分页器算总页数的唯一依据，越界时如果回 0，页码控件会从 99 页突然塌成 1 页。
     * 另外用 size=1 验一遍 page=2，确认 offset 算的确实是 {@code (page-1)*size}。
     */
    @Test
    void pageBeyondLastKeepsRealTotalAndReturnsEmptyList() throws Exception {
        Account account = newUser("pz");
        long first = createSession(account.token(), null);
        long second = createSession(account.token(), null);

        Resp beyond = get("/api/sessions?page=99&size=20", account.token());
        assertThat(beyond.status()).isEqualTo(200);
        assertThat(beyond.body())
                .contains("\"total\":2")
                .contains("\"page\":99")
                .contains("\"list\":[]");
        assertThat(sessionIds(beyond.body())).isEmpty();

        Resp secondPage = get("/api/sessions?page=2&size=1", account.token());
        assertThat(secondPage.status()).isEqualTo(200);
        assertThat(secondPage.body()).contains("\"total\":2");
        assertThat(sessionIds(secondPage.body())).containsExactly(first).doesNotContain(second);
    }

    /**
     * size=1 逐条往回翻：游标条件写成 {@code id <= ?} 会重复一条，写成 {@code id < ? - 1} 会漏一条。
     * 三次翻页拼起来必须正好是全部消息，不重不漏。
     */
    @Test
    void singleItemPagesWalkBackwardsWithoutDuplicates() throws Exception {
        Account account = newUser("s1");
        long sessionId = createSession(account.token(), null);
        List<Long> inserted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            inserted.add(insertMessage(sessionId, account.userId(),
                    i % 2 == 0 ? "user" : "assistant", "one-" + i));
        }

        List<Long> walked = new ArrayList<>();
        String query = "?size=1";
        for (int page = 1; page <= 3; page++) {
            Resp resp = get("/api/sessions/" + sessionId + "/messages" + query, account.token());
            assertThat(resp.status()).isEqualTo(200);
            List<Long> ids = messageIds(resp.body());
            assertThat(ids).as("第 %d 页只该有一条", page).hasSize(1);
            walked.addAll(ids);
            if (page < 3) {
                assertThat(resp.body()).as("第 %d 页还有更早的", page).contains("\"hasMore\":true");
                query = "?size=1&cursor=" + nextCursor(resp.body());
            } else {
                assertThat(resp.body()).contains("\"hasMore\":false").contains("\"nextCursor\":null");
            }
        }

        // 从最新往回走，顺序必然是插入顺序的倒序
        assertThat(walked).doesNotHaveDuplicates()
                .containsExactly(inserted.get(2), inserted.get(1), inserted.get(0));
    }

    /**
     * refNoteIds / refConceptIds：有值时原样读出，NULL 时必须是 {@code []}。
     * 这两个字段是「参考了你的 N 条笔记」的唯一数据源——返回 null 前端会渲染成
     * 「参考了 null 条」，抛异常则整页历史都打不开。
     */
    @Test
    void referenceIdsReadAsArraysAndNullBecomesEmptyList() throws Exception {
        Account account = newUser("rf");
        long sessionId = createSession(account.token(), null);

        Long withRefs = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state,
                        interrupt_reason, ref_note_ids, ref_concept_ids, created_at, updated_at)
                VALUES (?, ?, 'assistant', 'answer with refs', 1, NULL, ?::bigint[], ?::bigint[], now(), now())
                RETURNING id
                """, Long.class, sessionId, account.userId(), "{3,7}", "{11}");
        long withoutRefs = insertMessage(sessionId, account.userId(), "user", "no refs");

        Resp resp = get("/api/sessions/" + sessionId + "/messages", account.token());

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body())
                .contains("\"refNoteIds\":[3,7]")
                .contains("\"refConceptIds\":[11]")
                .contains("\"refNoteIds\":[]")
                .contains("\"refConceptIds\":[]")
                .doesNotContain("\"refNoteIds\":null")
                .doesNotContain("\"refConceptIds\":null");
        assertThat(messageIds(resp.body())).containsExactly(withRefs, withoutRefs);
    }

    /**
     * 僵尸收敛只在「第一页」（cursor 缺省）做，带游标翻历史不应该写库：
     * 一次滚动会发十几个请求，每个都跑一条 UPDATE 是把写压力白白放大十倍。
     *
     * <p>用例先带游标请求一次（僵尸必须原样返回、库里一行不能改），
     * 再请求第一页做对照（这时才收敛）——差别只能来自 cursor 有没有传。
     */
    @Test
    void zombieReconcileOnlyHappensOnFirstPage() throws Exception {
        Account account = newUser("zc");
        long sessionId = createSession(account.token(), null);

        Long zombie = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, created_at, updated_at)
                VALUES (?, ?, 'assistant', '', 0, now() - interval '30 minutes', now() - interval '30 minutes')
                RETURNING id
                """, Long.class, sessionId, account.userId());
        long newest = insertMessage(sessionId, account.userId(), "user", "after the zombie");

        Resp withCursor = get("/api/sessions/" + sessionId + "/messages?cursor=" + newest, account.token());
        assertThat(withCursor.status()).isEqualTo(200);
        assertThat(messageIds(withCursor.body())).containsExactly(zombie);
        assertThat(withCursor.body()).contains("\"streamState\":0").doesNotContain("SHUTDOWN");
        assertThat(streamStateOf(zombie)).isZero();
        assertThat(interruptReasonOf(zombie)).isNull();

        Resp firstPage = get("/api/sessions/" + sessionId + "/messages", account.token());
        assertThat(firstPage.body()).contains("\"streamState\":2").contains("SHUTDOWN");
        assertThat(streamStateOf(zombie)).isEqualTo(2);
    }

    /**
     * 读时收敛的阈值必须落在 (200s, 220s] 这个区间里。
     *
     * <p>为什么要两个只差 20 秒的样本：其它僵尸用例造的都是 30 分钟前的行，1800s 远大于任何
     * 合理阈值——阈值改回 120s、甚至改成 60s，它们照样全绿，常量被改错时没有任何测试会红。
     * 这里把阈值夹在中间：改回 120s 会让 200s 那条被误杀，调到 240s 会让 220s 那条不被收敛。
     *
     * <p>阈值改了这条就会红，这是故意的。真要调，先确认它还大于
     * {@code shizhi.model.total-timeout}（留 30 秒余量），再改这里的两个边界值。
     */
    @Test
    void reconcileThresholdSitsBetween200And220Seconds() throws Exception {
        Account account = newUser("th");
        long sessionId = createSession(account.token(), null);

        long justUnder = insertStaleGenerating(sessionId, account.userId(), 200);
        long justOver = insertStaleGenerating(sessionId, account.userId(), 220);

        Resp resp = get("/api/sessions/" + sessionId + "/messages", account.token());
        assertThat(resp.status()).isEqualTo(200);

        // 200 秒：还在阈值之内，一行都不能动（阈值若被改小，这里立刻红）
        assertThat(streamStateOf(justUnder)).isZero();
        assertThat(interruptReasonOf(justUnder)).isNull();
        assertThat(resp.body()).contains(
                "\"id\":" + justUnder + ",\"role\":\"assistant\",\"content\":\"still streaming\",\"streamState\":0");

        // 220 秒：必须已经收敛成「中断 / SHUTDOWN」（阈值若被改大，这里立刻红）
        assertThat(streamStateOf(justOver)).isEqualTo(2);
        assertThat(interruptReasonOf(justOver)).isEqualTo("SHUTDOWN");
        assertThat(resp.body()).contains("\"streamState\":2").contains("SHUTDOWN");
    }

    /**
     * 客户端带 {@code Accept: text/event-stream} 时，失败的响应仍然必须是普通 JSON。
     *
     * <p>真实前端的发消息请求就带这个头。如果全局异常处理器不显式声明 {@code application/json}，
     * Spring 会按 Accept 去找能把 {@code ApiResponse} 写成 SSE 的转换器——找不到就抛第二次异常，
     * 于是「访问别人的会话」这个本该 403 的场景变成 500，前端连 {@code code=1004} 都拿不到。
     * 这条守的就是「内容协商不能把错误码带偏」。
     */
    @Test
    void errorStaysJsonEvenWhenClientAcceptsEventStream() throws Exception {
        Account owner = newUser("aes");
        long sessionId = createSession(owner.token(), null);
        Account stranger = newUser("aet");

        HttpResponse<String> response = postAcceptingEventStream(
                "/api/sessions/" + sessionId + "/messages",
                """
                        {"content":"偷看别人的会话"}
                        """,
                stranger.token());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("错误响应必须是 application/json，不能被 Accept 协商成 SSE（那不是 403，是 500）")
                .contains("application/json")
                .doesNotContain("text/event-stream");
        assertThat(response.body()).contains("\"code\":1004");
        // 开流之前的失败不该留下任何消息行
        assertThat(rawMessageCount(sessionId)).isZero();
    }

    /**
     * {@code stream_state=3}（一个字都没答出来）的行不进历史，但必须还在库里。
     *
     * <p>两段缺一不可：
     * <ul>
     *   <li><b>接口侧</b>：历史 list 里既要有正常回答、又不能有失败行。只断言「不含」的话，
     *       「历史返回空列表」这种退化也能让它通过；</li>
     *   <li><b>库里侧</b>：那一行仍在、状态仍是 3。证明这是<b>查询层过滤</b>而不是把失败痕迹删了——
     *       否则将来谁把过滤改成「失败就删行」，接口表现一模一样，可重试历史和排查依据都没了，
     *       而不会有任何测试发现。</li>
     * </ul>
     *
     * <p>为什么要过滤：重试失败会一次留下一条空行，同一轮问答在历史里越堆越多，而状态 3 本来就不渲染气泡。
     */
    @Test
    void failedEmptyAnswerIsHiddenFromHistoryButKeptInDatabase() throws Exception {
        Account account = newUser("s3h");
        long sessionId = createSession(account.token(), null);

        long kept = insertMessage(sessionId, account.userId(), "assistant", "正常回答");
        long failed = insertMessageWithState(sessionId, account.userId(), "assistant", "", 3);

        Resp history = get("/api/sessions/" + sessionId + "/messages", account.token());

        assertThat(history.status()).isEqualTo(200);
        assertThat(messageIds(history.body()))
                .as("正常回答必须在，失败空行必须不在")
                .contains(kept)
                .doesNotContain(failed);
        assertThat(history.body()).doesNotContain("\"streamState\":3");

        // 库里那一行还在，且状态没被改动——过滤发生在查询层
        assertThat(streamStateOf(failed)).isEqualTo(3);
        assertThat(rawMessageCount(sessionId)).isEqualTo(2);
    }

    /**
     * {@code errors} 只在字段级校验失败时出现。业务失败里带上它，会骗前端去标红输入框，
     * 而这条请求根本没有输入框。
     *
     * <p>反面同样要守：成功响应里 {@code title}、{@code interruptReason}、{@code supersededBy}、
     * {@code nextCursor} 这些 null 字段必须出现（三个 DTO 上的 {@code @JsonInclude(ALWAYS)} 就是
     * 为这个加的），同时它们不能把 {@code errors} 一起带出来。
     */
    @Test
    void businessFailuresOmitErrorsAndSuccessKeepsNullFields() throws Exception {
        Account account = newUser("js");
        long sessionId = createSession(account.token(), null);

        Resp missing = get("/api/sessions/999999999/messages", account.token());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":2004").doesNotContain("\"errors\"");

        Account stranger = newUser("jf");
        Resp foreign = get("/api/sessions/" + sessionId + "/messages", stranger.token());
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.body()).contains("\"code\":1004").doesNotContain("\"errors\"");

        Resp created = post("/api/sessions", null, account.token());
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body())
                .contains("\"title\":null")
                .contains("\"skillCode\":null")
                .doesNotContain("\"errors\"");

        insertMessage(sessionId, account.userId(), "user", "hi");
        Resp history = get("/api/sessions/" + sessionId + "/messages", account.token());
        assertThat(history.body())
                .contains("\"interruptReason\":null")
                .contains("\"supersededBy\":null")
                .contains("\"nextCursor\":null")
                .doesNotContain("\"errors\"");

        // 反证：字段级校验失败时 errors 必须出现，否则「errors 永远不出现」也能骗过上面所有断言
        Resp invalid = get("/api/sessions?size=101", account.token());
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"errors\"").contains("\"field\":\"size\"");
    }

    /**
     * 三种「没指定技能」的建会话请求都必须成功，且空白技能要按 null 落库。
     * 前端进聊天页可能发 noBody、发 {@code {}}、或者把输入框里的空格一起发过来。
     * 库里存下一个全是空格的 skill_code 不会报错，但技能路由从此永远命中不到它，
     * 排查时还会以为是路由算法的问题——所以空白必须在入口归一成 null。
     */
    @Test
    void createSessionWithoutBodyEmptyObjectAndBlankSkillAllSucceed() throws Exception {
        Account account = newUser("bd");

        Resp noBody = post("/api/sessions", null, account.token());
        Resp emptyObject = post("/api/sessions", "{}", account.token());
        Resp blankSkill = post("/api/sessions", """
                {"skillCode":"   "}
                """, account.token());

        for (Resp resp : List.of(noBody, emptyObject, blankSkill)) {
            assertThat(resp.status()).isEqualTo(200);
            assertThat(resp.body()).contains("\"code\":0").contains("\"skillCode\":null");
        }

        long blankSessionId = sessionIds(blankSkill.body()).get(0);
        assertThat(skillCodeOf(blankSessionId)).isNull();
        // title 建会话时必须为空，发第一条消息时才截取问题的前 30 个字
        assertThat(titleOf(blankSessionId)).isNull();
        // last_active_at 建会话时就写 now()，否则会话建完在列表里排不到最前
        assertThat(hasLastActiveAt(blankSessionId)).isTrue();
    }

    /**
     * 两个会话的 last_active_at 完全相同时，必须靠 {@code id DESC} 决出先后。
     * 否则同一个用户连点两次「新建会话」，列表顺序会在两次请求之间抖动——
     * 前端拿这个列表做 key 渲染时会出现无谓的重排。
     */
    @Test
    void identicalLastActiveFallsBackToIdDesc() throws Exception {
        Account account = newUser("tb");
        long older = createSession(account.token(), null);
        long newer = createSession(account.token(), null);
        jdbc.update("""
                UPDATE chat_session SET last_active_at = TIMESTAMPTZ '2026-01-01 00:00:00+08'
                WHERE id IN (?, ?)
                """, older, newer);

        Resp resp = get("/api/sessions", account.token());

        assertThat(resp.status()).isEqualTo(200);
        assertThat(sessionIds(resp.body())).containsExactly(newer, older);
    }

    @Test
    void renamesAndDeletesSession() throws Exception {
        Account owner = newUser("rn");
        long sessionId = createSession(owner.token(), "{}");
        insertMessage(sessionId, owner.userId(), "user", "一个问题");

        Resp renamed = patch("/api/sessions/" + sessionId, "{\"title\":\"换个名字\"}", owner.token());
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(titleOf(sessionId)).isEqualTo("换个名字");

        // 改名只动标题，消息不能被顺手清掉
        assertThat(rawMessageCount(sessionId)).isEqualTo(1);

        Account stranger = newUser("rs");
        assertThat(patch("/api/sessions/" + sessionId, "{\"title\":\"抢改\"}", stranger.token()).status())
                .isEqualTo(403);
        assertThat(delete("/api/sessions/" + sessionId, stranger.token()).status()).isEqualTo(403);

        Resp deleted = delete("/api/sessions/" + sessionId, owner.token());
        assertThat(deleted.status()).isEqualTo(200);
        // 现在是软删：会话还在，只是打了删除标记，消息一条没动，30 天内可恢复
        assertThat(rawMessageCount(sessionId)).isEqualTo(1);
        Integer sessionRows = jdbc.queryForObject(
                "SELECT count(*) FROM chat_session WHERE id = ? AND deleted_at IS NOT NULL",
                Integer.class, sessionId);
        assertThat(sessionRows).isEqualTo(1);
    }

    @Test
    void pinnedSessionMovesToTop() throws Exception {
        Account owner = newUser("pn");
        long older = createSession(owner.token(), "{}");
        Thread.sleep(5);
        createSession(owner.token(), "{}");

        assertThat(patch("/api/sessions/" + older + "/pin", "{\"pinned\":true}", owner.token()).status())
                .isEqualTo(200);

        Resp list = get("/api/sessions?page=1&size=10", owner.token());
        assertThat(sessionIds(list.body())).startsWith(older);
    }

    @Test
    void deletedSessionGoesToTrashAndCanBeRestored() throws Exception {
        Account owner = newUser("tr");
        long sessionId = createSession(owner.token(), "{}");

        assertThat(delete("/api/sessions/" + sessionId, owner.token()).status()).isEqualTo(200);

        // 软删：列表看不见，回收站看得见
        assertThat(sessionIds(get("/api/sessions?page=1&size=10", owner.token()).body()))
                .doesNotContain(sessionId);
        assertThat(sessionIds(get("/api/sessions/trash", owner.token()).body()))
                .contains(sessionId);

        assertThat(post("/api/sessions/" + sessionId + "/restore", "{}", owner.token()).status())
                .isEqualTo(200);
        assertThat(sessionIds(get("/api/sessions?page=1&size=10", owner.token()).body()))
                .contains(sessionId);

        // 再删一次，然后彻底删除，回收站里也不该再有
        assertThat(delete("/api/sessions/" + sessionId, owner.token()).status()).isEqualTo(200);
        assertThat(delete("/api/sessions/" + sessionId + "/purge", owner.token()).status())
                .isEqualTo(200);
        assertThat(sessionIds(get("/api/sessions/trash", owner.token()).body()))
                .doesNotContain(sessionId);
    }

    @Test
    void batchOperationsHandleMultipleSessions() throws Exception {
        Account owner = newUser("bt");
        long first = createSession(owner.token(), "{}");
        long second = createSession(owner.token(), "{}");
        String ids = "[" + first + "," + second + "]";

        assertThat(post("/api/sessions/batch/delete", "{\"ids\":" + ids + "}", owner.token()).status())
                .isEqualTo(200);
        assertThat(sessionIds(get("/api/sessions?page=1&size=10", owner.token()).body()))
                .doesNotContain(first, second);
        assertThat(sessionIds(get("/api/sessions/trash", owner.token()).body()))
                .contains(first, second);

        assertThat(post("/api/sessions/batch/restore", "{\"ids\":" + ids + "}", owner.token()).status())
                .isEqualTo(200);
        assertThat(sessionIds(get("/api/sessions?page=1&size=10", owner.token()).body()))
                .contains(first, second);

        assertThat(post("/api/sessions/batch/delete", "{\"ids\":" + ids + "}", owner.token()).status())
                .isEqualTo(200);
        assertThat(post("/api/sessions/batch/purge", "{\"ids\":" + ids + "}", owner.token()).status())
                .isEqualTo(200);
        assertThat(sessionIds(get("/api/sessions/trash", owner.token()).body()))
                .doesNotContain(first, second);
    }

    @Test
    void contextEndpointReportsEmptySession() throws Exception {
        Account owner = newUser("cx");
        long sessionId = createSession(owner.token(), "{}");

        Resp resp = get("/api/sessions/" + sessionId + "/context", owner.token());
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("\"usedTokens\":0");
        assertThat(resp.body()).contains("\"contextWindow\":32768");
    }

    private Account newUser(String prefix) throws Exception {
        String username = prefix + UUID.randomUUID().toString().substring(0, 8);
        Resp registered = post("/api/auth/register", """
                {"username":"%s","email":"%s@example.com","password":"password123"}
                """.formatted(username, username), null);
        assertThat(registered.status()).isEqualTo(200);

        Resp login = post("/api/auth/login", """
                {"account":"%s","password":"password123"}
                """.formatted(username), null);
        assertThat(login.status()).isEqualTo(200);
        String token = jsonString(login.body(), "accessToken");
        assertThat(token).isNotBlank();

        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        assertThat(userId).isNotNull();
        return new Account(username, token, userId);
    }

    private long createSession(String token, String json) throws Exception {
        Resp resp = post("/api/sessions", json, token);
        assertThat(resp.status()).isEqualTo(200);
        List<Long> ids = sessionIds(resp.body());
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private long insertMessage(long sessionId, long userId, String role, String content) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, now(), now())
                RETURNING id
                """, Long.class, sessionId, userId, role, content);
        assertThat(id).isNotNull();
        return id;
    }

    private Integer streamStateOf(Long messageId) {
        return jdbc.queryForObject("SELECT stream_state FROM chat_message WHERE id = ?", Integer.class, messageId);
    }

    private String interruptReasonOf(Long messageId) {
        return jdbc.queryForObject("SELECT interrupt_reason FROM chat_message WHERE id = ?", String.class, messageId);
    }

    /**
     * 插一条「多久以前更新过、仍停在生成中」的占位行，用来卡收敛阈值的边界。
     *
     * @param ageSeconds 距现在多少秒之前发生最后一次 flush（即 updated_at 的年龄）
     */
    private long insertStaleGenerating(long sessionId, long userId, int ageSeconds) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, created_at, updated_at)
                VALUES (?, ?, 'assistant', 'still streaming', 0,
                        now() - (? * interval '1 second'), now() - (? * interval '1 second'))
                RETURNING id
                """, Long.class, sessionId, userId, ageSeconds, ageSeconds);
        assertThat(id).isNotNull();
        return id;
    }

    private String skillCodeOf(long sessionId) {
        return jdbc.queryForObject("SELECT skill_code FROM chat_session WHERE id = ?", String.class, sessionId);
    }

    /** 插一条指定状态的行，绕过接口直接造样本（状态 3 只有失败路径才会产生，接口造不出来）。 */
    private long insertMessageWithState(long sessionId, long userId, String role, String content, int streamState) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                RETURNING id
                """, Long.class, sessionId, userId, role, content, streamState);
        assertThat(id).isNotNull();
        return id;
    }

    /** 某个会话在库里实际有多少行——用来证明「接口看不见」不等于「库里没有」。 */
    private int rawMessageCount(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    /**
     * 发一次带 {@code Accept: text/event-stream} 的请求，并把响应头也带回来。
     *
     * <p>单独一个方法而不是给 {@link Resp} 加字段：{@code Resp} 是全类共用的结构，
     * 为一条用例改它会把 21 条既有用例一起牵动。
     */
    private HttpResponse<String> postAcceptingEventStream(String path, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String titleOf(long sessionId) {
        return jdbc.queryForObject("SELECT title FROM chat_session WHERE id = ?", String.class, sessionId);
    }

    private boolean hasLastActiveAt(long sessionId) {
        Boolean present = jdbc.queryForObject(
                "SELECT last_active_at IS NOT NULL FROM chat_session WHERE id = ?", Boolean.class, sessionId);
        return Boolean.TRUE.equals(present);
    }

    private Resp post(String path, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .POST(json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body());
    }

    private Resp patch(String path, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body());
    }

    private Resp delete(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path)).DELETE();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body());
    }

    private Resp get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body());
    }

    private static List<Long> sessionIds(String body) {
        return collect(body, SESSION_IDS);
    }

    private static List<Long> messageIds(String body) {
        return collect(body, MESSAGE_IDS);
    }

    private static List<Long> collect(String body, Pattern pattern) {
        List<Long> ids = new ArrayList<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        return ids;
    }

    private static long nextCursor(String body) {
        Matcher matcher = Pattern.compile("\"nextCursor\":(\\d+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static void assertHasIsoTime(String body, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\":\"" + ISO_TIME_BODY + "\"");
        assertThat(pattern.matcher(body).find())
                .as("%s 应为带时区的 ISO-8601 时间，响应体：%s", field, body)
                .isTrue();
    }

    private static String jsonString(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }
}
