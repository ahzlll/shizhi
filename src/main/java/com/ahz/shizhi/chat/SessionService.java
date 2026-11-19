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
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final TransactionTemplate transaction;

    public SessionService(SessionRepository sessions, MessageRepository messages,
                          TransactionTemplate transaction) {
        this.sessions = sessions;
        this.messages = messages;
        this.transaction = transaction;
    }

    /** 改名。影响行数为 0 时再查一次归属，好把「不存在」和「不是你的」分开报。 */
    public SessionView rename(long userId, long sessionId, String title) {
        int updated = sessions.updateTitle(sessionId, userId, title.strip());
        if (updated == 0) {
            requireOwnership(userId, sessionId);
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return sessions.findById(sessionId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    /**
     * 置顶 / 取消置顶。影响行数为 0 时再查一次归属，和改名一样区分「不存在」与「不是你的」。
     */
    public SessionView pin(long userId, long sessionId, boolean pinned) {
        int updated = sessions.updatePinned(sessionId, userId, pinned);
        if (updated == 0) {
            requireOwnership(userId, sessionId);
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return sessions.findById(sessionId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    /**
     * 删除会话：移进回收站，不真删。
     *
     * <p>以前是连消息一起硬删，手滑一次就找不回来。现在只打 {@code deleted_at}，
     * 列表和搜索都不再返回，30 天后由定时任务彻底清理。
     */
    public void delete(long userId, long sessionId) {
        requireOwnership(userId, sessionId);
        if (sessions.deleteById(sessionId, userId) == 0) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
    }

    /** 批量移入回收站，返回实际受影响条数。 */
    public int deleteBatch(long userId, List<Long> ids) {
        return sessions.softDeleteBatch(userId, ids);
    }

    public int restoreBatch(long userId, List<Long> ids) {
        return sessions.restoreBatch(userId, ids);
    }

    public int purgeBatch(long userId, List<Long> ids) {
        return sessions.purgeBatch(userId, ids);
    }

    public List<SessionView> trash(long userId) {
        return sessions.findDeleted(userId);
    }

    /** 恢复：清掉删除标记，会话回到列表里，消息一条没少。 */
    public SessionView restore(long userId, long sessionId) {
        if (sessions.restore(sessionId, userId) == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "回收站里没有这条会话");
        }
        return sessions.findById(sessionId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    /** 彻底删除，只对回收站里的会话生效，防止绕过回收站直接抹掉一条正常会话。 */
    public void purge(long userId, long sessionId) {
        if (sessions.purge(sessionId, userId) == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "回收站里没有这条会话");
        }
    }

    /** 定时任务调用：清掉超过保留期的会话。 */
    public int purgeExpired(int retentionDays) {
        return sessions.purgeExpired(retentionDays);
    }

    /** 请求体整个都可以不带，所以这里必须接住 null——不带 body 建会话是正常调用。 */
    public SessionView create(long userId, CreateSessionRequest request) {
        return sessions.insert(userId, normalizeSkillCode(request));
    }

    /** 关键词搜会话：标题命中或任意消息正文命中。空关键词直接返回空，别把整库拉出来。 */
    public List<SessionView> search(long userId, String keyword) {
        String trimmed = keyword == null ? "" : keyword.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return sessions.search(userId, trimmed, 20);
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
     * 会话归属校验。两种失败分开报：查不到会话是 2004，别人的会话是 1004。
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
