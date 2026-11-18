import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import {
  createSession,
  deleteSession,
  listMessages,
  listSessions,
  pinSession,
  regenerate,
  renameSession,
  sendMessage,
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
    nextCursor.value = null
    hasMore.value = false
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

  async function selectSession(id: number) {
    if (currentId.value === id) return
    currentId.value = id
    messages.value = []
    draft.value = null
    nextCursor.value = null
    hasMore.value = false
    await loadHistory()
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
        messages.value.push(buildAssistantMessage(payload.messageId, draft.value.content))
        draft.value = null
        // 标题与消息数在流结束后才定下来，重新拉一次列表比本地猜更准
        void loadSessions()
      },
      onError: (payload) => {
        if (draft.value) {
          draft.value.state = 3
          draft.value.error = payload.message || '模型调用失败'
        }
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
      if (draft.value?.state === 0) draft.value.state = 3
    } catch (error) {
      if (isAbort(error)) {
        if (draft.value) draft.value.state = 2
      } else if (draft.value) {
        draft.value.state = 3
        draft.value.error = error instanceof Error ? error.message : '请求失败'
      }
    } finally {
      controller = null
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
      if (draft.value?.state === 0) draft.value.state = 3
    } catch (error) {
      if (isAbort(error)) {
        if (draft.value) draft.value.state = 2
      } else if (draft.value) {
        draft.value.state = 3
        draft.value.error = error instanceof Error ? error.message : '请求失败'
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
    loadSessions,
    ensureSession,
    newSession,
    pin,
    rename,
    remove,
    selectSession,
    loadHistory,
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
  }
}

function buildAssistantMessage(id: number, content: string): MessageView {
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
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
