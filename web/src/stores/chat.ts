import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import {
  batchDeleteSessions,
  createSession,
  deleteSession,
  editMessage,
  listMessages,
  listSessions,
  pinSession,
  regenerate,
  renameSession,
  getSessionContext,
  searchSessions,
  sendMessage,
  type SessionContext,
  type DonePayload,
  type MessageView,
  type SessionView,
  type StreamHandlers,
} from '@/api/chat'

export interface DraftMessage {
  /** 还没拿到后端 id 时为 null */
  id: number | null
  content: string
  /** 0 生成中、2 中断、3 失败 */
  state: 0 | 2 | 3
  error?: string
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionView[]>([])
  const loadingSessions = ref(false)

  const currentId = ref<number | null>(null)
  const messages = ref<MessageView[]>([])
  const loadingMessages = ref(false)
  const nextCursor = ref<number | null>(null)
  const hasMore = ref(false)

  const draft = ref<DraftMessage | null>(null)
  const streaming = computed(() => draft.value?.state === 0)

  const searchResults = ref<SessionView[]>([])
  const searching = ref(false)
  /** 发送失败且没落库时，把原文交还给输入框 */
  const restoreText = ref<string | null>(null)
  /** 当前会话的模型与上下文占用，输入框上方展示 */
  const context = ref<SessionContext | null>(null)

  let controller: AbortController | null = null

  async function loadSessions() {
    loadingSessions.value = true
    try {
      const page = await listSessions(1, 50)
      sessions.value = page.list
    } finally {
      loadingSessions.value = false
    }
  }

  async function ensureSession(): Promise<number> {
    if (currentId.value) return currentId.value
    return newSession()
  }

  async function newSession(): Promise<number> {
    const session = await createSession()
    sessions.value = [session, ...sessions.value]
    currentId.value = session.id
    messages.value = []
    draft.value = null
    context.value = null
    nextCursor.value = null
    hasMore.value = false
    void loadContext(session.id)
    return session.id
  }

  async function pin(id: number, pinned: boolean) {
    const updated = await pinSession(id, pinned)
    const target = sessions.value.find((item) => item.id === id)
    if (target) target.pinned = updated.pinned
    sessions.value = [...sessions.value].sort((left, right) => {
      if (left.pinned !== right.pinned) return left.pinned ? -1 : 1
      return new Date(right.lastActiveAt).getTime() - new Date(left.lastActiveAt).getTime()
    })
  }

  async function rename(id: number, title: string) {
    const updated = await renameSession(id, title)
    const target = sessions.value.find((item) => item.id === id)
    if (target) target.title = updated.title
  }

  async function remove(id: number) {
    await deleteSession(id)
    sessions.value = sessions.value.filter((item) => item.id !== id)
    if (currentId.value !== id) return

    currentId.value = null
    messages.value = []
    draft.value = null
    if (sessions.value.length > 0) {
      await selectSession(sessions.value[0].id)
    }
  }

  /** 批量移入回收站。选中的会话里有当前会话时，切到剩下最靠前的一个。 */
  async function batchRemove(ids: number[]) {
    if (ids.length === 0) return
    await batchDeleteSessions(ids)
    sessions.value = sessions.value.filter((item) => !ids.includes(item.id))
    if (!currentId.value || !ids.includes(currentId.value)) return

    currentId.value = null
    messages.value = []
    draft.value = null
    context.value = null
    if (sessions.value.length > 0) {
      await selectSession(sessions.value[0].id)
    }
  }

  async function search(keyword: string) {
    const trimmed = keyword.trim()
    if (!trimmed) {
      searchResults.value = []
      return
    }
    searching.value = true
    try {
      searchResults.value = await searchSessions(trimmed)
    } finally {
      searching.value = false
    }
  }

  /**
   * 编辑已发出的提问并重新回答。后端会删掉这条消息之后的问答，
   * 前端同步截断，避免界面上残留已经不存在的消息。
   */
  async function editResend(messageId: number, content: string) {
    if (streaming.value) return
    const index = messages.value.findIndex((item) => item.id === messageId)
    if (index < 0) return

    messages.value = messages.value.slice(0, index)
    const tempUserId = -Date.now()
    messages.value.push(buildUserMessage(tempUserId, content))
    draft.value = { id: null, content: '', state: 0 }

    controller = new AbortController()
    try {
      await editMessage(messageId, content, handlers(tempUserId), controller.signal)
      if (draft.value?.state === 0) failDraft()
    } catch (error) {
      if (isAbort(error)) {
        if (draft.value) draft.value.state = 2
      } else {
        failDraft(error instanceof Error ? error.message : '请求失败')
      }
    } finally {
      controller = null
    }
  }

  /** 回到空白状态：不选中任何会话，也不新建，等用户真正发问时再建。 */
  function startNew() {
    currentId.value = null
    messages.value = []
    draft.value = null
    context.value = null
    nextCursor.value = null
    hasMore.value = false
    searchResults.value = []
  }

  async function selectSession(id: number) {
    if (currentId.value === id) return
    currentId.value = id
    messages.value = []
    draft.value = null
    context.value = null
    nextCursor.value = null
    hasMore.value = false
    await Promise.all([loadHistory(), loadContext(id)])
  }

  async function loadContext(sessionId: number) {
    try {
      context.value = await getSessionContext(sessionId)
    } catch {
      // 上下文展示失败不影响对话本身
    }
  }

  async function loadHistory() {
    if (!currentId.value) return
    loadingMessages.value = true
    try {
      const page = await listMessages(currentId.value, nextCursor.value)
      messages.value = nextCursor.value ? [...page.list, ...messages.value] : page.list
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
    } finally {
      loadingMessages.value = false
    }
  }

  /** 有内容就按「中断」收敛，和后台判 2/3 的口径一致；否则界面上会出现「有回答却写着没答出来」。 */
  function failDraft(message?: string) {
    if (!draft.value) return
    draft.value.state = draft.value.content.trim() ? 2 : 3
    if (message) draft.value.error = message
  }

  function handlers(tempUserId: number): StreamHandlers {
    return {
      onAck: ({ userMessageId, assistantMessageId }) => {
        const userMessage = messages.value.find((item) => item.id === tempUserId)
        if (userMessage) userMessage.id = userMessageId
        if (draft.value) draft.value.id = assistantMessageId
      },
      onDelta: (text) => {
        if (draft.value) draft.value.content += text
      },
      onDone: (payload: DonePayload) => {
        if (!draft.value) return
        messages.value.push(
          buildAssistantMessage(
            payload.messageId,
            draft.value.content,
            payload.promptTokens,
            payload.completionTokens,
            payload.model,
          ),
        )
        draft.value = null
        // 标题、消息数与上下文占用在流结束后才定下来，重新拉一次比本地猜更准
        void loadSessions()
        if (currentId.value) void loadContext(currentId.value)
      },
      onError: (payload) => {
        failDraft(payload.message || '模型调用失败')
      },
    }
  }

  async function send(content: string) {
    const text = content.trim()
    if (!text || streaming.value) return

    const sessionId = await ensureSession()
    const tempUserId = -Date.now()
    messages.value.push(buildUserMessage(tempUserId, text))
    draft.value = { id: null, content: '', state: 0 }

    controller = new AbortController()
    try {
      await sendMessage(sessionId, text, handlers(tempUserId), controller.signal)
      if (draft.value?.state === 0) failDraft()
    } catch (error) {
      if (isAbort(error)) {
        if (draft.value) draft.value.state = 2
      } else {
        failDraft(error instanceof Error ? error.message : '请求失败')
      }
    } finally {
      controller = null
      // 没有收到 ack 说明这条提问根本没落库：撤掉乐观插入的那条和空的回答占位，
      // 把原文放回输入框，别让用户对着一条不存在的消息和一片空白
      if (draft.value && draft.value.id == null) {
        messages.value = messages.value.filter((item) => item.id !== tempUserId)
        draft.value = null
        restoreText.value = text
      }
    }
  }

  async function retry(messageId: number) {
    if (streaming.value) return
    const index = messages.value.findIndex((item) => item.id === messageId)
    if (index >= 0) messages.value.splice(index, 1)
    draft.value = { id: null, content: '', state: 0 }

    controller = new AbortController()
    try {
      await regenerate(messageId, handlers(-1), controller.signal)
      if (draft.value?.state === 0) failDraft()
    } catch (error) {
      if (isAbort(error)) {
        if (draft.value) draft.value.state = 2
      } else {
        failDraft(error instanceof Error ? error.message : '请求失败')
      }
    } finally {
      controller = null
    }
  }

  function stop() {
    controller?.abort()
  }

  function reset() {
    sessions.value = []
    currentId.value = null
    messages.value = []
    draft.value = null
    nextCursor.value = null
    hasMore.value = false
  }

  return {
    sessions,
    loadingSessions,
    currentId,
    messages,
    loadingMessages,
    hasMore,
    draft,
    streaming,
    searchResults,
    searching,
    restoreText,
    context,
    loadContext,
    loadSessions,
    ensureSession,
    newSession,
    startNew,
    pin,
    rename,
    remove,
    batchRemove,
    selectSession,
    loadHistory,
    search,
    editResend,
    send,
    retry,
    stop,
    reset,
  }
})

function buildUserMessage(id: number, content: string): MessageView {
  return {
    id,
    role: 'user',
    content,
    streamState: 1,
    interruptReason: null,
    supersededBy: null,
    refNoteIds: null,
    refConceptIds: null,
    createdAt: new Date().toISOString(),
    promptTokens: null,
    completionTokens: null,
    model: null,
  }
}

function buildAssistantMessage(
  id: number,
  content: string,
  promptTokens: number | null = null,
  completionTokens: number | null = null,
  model: string | null = null,
): MessageView {
  return {
    id,
    role: 'assistant',
    content,
    streamState: 1,
    interruptReason: null,
    supersededBy: null,
    refNoteIds: null,
    refConceptIds: null,
    createdAt: new Date().toISOString(),
    promptTokens,
    completionTokens,
    model,
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
