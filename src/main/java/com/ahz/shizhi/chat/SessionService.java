package com.ahz.shizhi.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ahz.shizhi.chat.dto.CreateSessionRequest;
import com.ahz.shizhi.chat.dto.MessageView;
import com.ahz.shizhi.chat.dto.SessionView;
import com.ahz.shizhi.common.api.CursorPage;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.api.PageResult;
import com.ahz.shizhi.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessions;
    private final MessageRepository messages;

    public SessionService(SessionRepository sessions, MessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    /** 请求体整个都可以不带，所以这里必须接住 null——不带 body 建会话是正常调用。 */
    public SessionView create(long userId, CreateSessionRequest request) {
        return sessions.insert(userId, normalizeSkillCode(request));
    }

    public PageResult<SessionView> list(long userId, int page, int size) {
        long total = sessions.countByUser(userId);
        if (total == 0) {
            return new PageResult<>(0L, page, size, List.of());
        }
        // 偏移量用 long 算：page 是用户传的，int 乘法会溢出成负数，变成一条语法正确的畸形 SQL
        long offset = (long) (page - 1) * size;
        List<SessionView> list = sessions.pageByUser(userId, size, offset);
        return new PageResult<>(total, page, size, list);
    }

    /**
     * 拉历史。归属校验的两种失败分开报：查不到会话是 2004，别人的会话是 1004。
     *
     * <p>第一页顺手做一次僵尸收敛——用户刷新时才有机会把挂住的「生成中」改掉，
     * 翻页时数据已经收敛过，不重复执行。
     */
    public CursorPage<MessageView> messages(long userId, long sessionId, Long cursor, int size) {
        requireOwnership(userId, sessionId);

        if (cursor == null) {
            reconcileZombies(sessionId);
        }

        // 多取一条用来判断还有没有下一页，比 count(*) 便宜且和游标语义天然契合
        List<MessageView> rows = messages.findBySession(sessionId, cursor, size + 1);
        boolean hasMore = rows.size() > size;
        List<MessageView> pageRows = hasMore ? rows.subList(0, size) : rows;

        Long nextCursor = hasMore ? pageRows.get(pageRows.size() - 1).id() : null;

        // 查询按 id 倒序拿到最近的一页，返回给前端翻转成时间正序——渲染时不用再排一次
        List<MessageView> ascending = new ArrayList<>(pageRows);
        Collections.reverse(ascending);
        return new CursorPage<>(ascending, nextCursor, hasMore);
    }

    /**
     * 僵尸收敛是「顺手把僵尸行改掉」，它的失败不该让整次查询失败：
     * 数据库抖一下就把历史列表变成 500，用户失去的是已经生成的内容。
     */
    private void reconcileZombies(long sessionId) {
        try {
            int converged = messages.reconcileZombies(sessionId);
            if (converged > 0) {
                log.info("惰性收敛僵尸消息 sessionId={} rows={}", sessionId, converged);
            }
        } catch (DataAccessException ex) {
            log.warn("惰性收敛僵尸消息失败，继续返回历史 sessionId={}", sessionId, ex);
        }
    }

    /**
     * 会话归属校验。两种失败必须分开报：查不到会话是 2004，别人的会话是 1004（04 §4.2）。
     * 包内可见——发消息也要用同一套判断，复制一份迟早会漂移。
     */
    void requireOwnership(long userId, long sessionId) {
        Long ownerId = sessions.findOwnerId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (ownerId != userId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该会话");
        }
    }

    /** 空白字符串和 null 是同一个意思：没有指定技能，交给系统路由。 */
    private static String normalizeSkillCode(CreateSessionRequest request) {
        if (request == null || request.skillCode() == null) {
            return null;
        }
        String trimmed = request.skillCode().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
