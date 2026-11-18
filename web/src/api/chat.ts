import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { createSseParser } from '@/utils/sse'

export interface SessionView {
  id: number
  title: string | null
  skillCode: string | null
  messageCount: number
  lastActiveAt: string
  createdAt: string
  pinned: boolean
}

export interface MessageView {
  id: number
  role: 'user' | 'assistant'
  content: string
  streamState: number
  interruptReason: string | null
  supersededBy: number | null
  refNoteIds: number[] | null
  refConceptIds: number[] | null
  createdAt: string
}

export interface PageResult<T> {
  total: number
  page: number
  size: number
  list: T[]
}

export interface CursorPage<T> {
  list: T[]
  nextCursor: number | null
  hasMore: boolean
}

export interface AckPayload {
  userMessageId: number
  assistantMessageId: number
  streamState: number
}

export interface DonePayload {
  messageId: number
  promptTokens: number
  completionTokens: number
  elapsedMs: number
  finishReason: string
}

export interface StreamHandlers {
  onAck?: (payload: AckPayload) => void
  onDelta?: (text: string) => void
  onDone?: (payload: DonePayload) => void
  onError?: (payload: { code: number; message: string }) => void
}

export function listSessions(page = 1, size = 50) {
  return http.get<PageResult<SessionView>>('/sessions', { page, size })
}

export function createSession() {
  return http.post<SessionView>('/sessions', {})
}

export function renameSession(sessionId: number, title: string) {
  return http.patch<SessionView>(`/sessions/${sessionId}`, { title })
}

export function pinSession(sessionId: number, pinned: boolean) {
  return http.patch<SessionView>(`/sessions/${sessionId}/pin`, { pinned })
}

export function deleteSession(sessionId: number) {
  return http.delete<void>(`/sessions/${sessionId}`)
}

export function listMessages(sessionId: number, cursor?: number | null, size = 20) {
  return http.get<CursorPage<MessageView>>(`/sessions/${sessionId}/messages`, {
    ...(cursor ? { cursor } : {}),
    size,
  })
}

export function sendMessage(
  sessionId: number,
  content: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
) {
  return stream(`/api/sessions/${sessionId}/messages`, { content }, handlers, signal)
}

export function regenerate(messageId: number, handlers: StreamHandlers, signal?: AbortSignal) {
  return stream(`/api/messages/${messageId}/regenerate`, {}, handlers, signal)
}

async function stream(
  url: string,
  body: unknown,
  handlers: StreamHandlers,
  signal: AbortSignal | undefined,
  retried = false,
) {
  const auth = useAuthStore()
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(auth.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
    },
    body: JSON.stringify(body ?? {}),
    signal,
  })

  if (response.status === 401 && !retried && auth.refreshToken) {
    const renewed = await auth.refresh()
    if (renewed) {
      return stream(url, body, handlers, signal, true)
    }
  }

  if (!response.ok) {
    const payload = await response.json().catch(() => null)
    throw new Error(payload?.message ?? `请求失败（${response.status}）`)
  }

  // 校验失败时后端返回的是普通 JSON，不会开流；这里先兜住，别拿 reader 去读一个 JSON 对象
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('text/event-stream')) {
    const payload = await response.json().catch(() => null)
    throw new Error(payload?.message ?? '服务端没有返回流式响应')
  }

  const reader = response.body?.getReader()
  if (!reader) throw new Error('浏览器不支持流式响应')

  const decoder = new TextDecoder()
  const parser = createSseParser(({ event, data }) => {
    const payload = safeParse(data)
    switch (event) {
      case 'ack':
        handlers.onAck?.(payload as unknown as AckPayload)
        break
      case 'delta':
        handlers.onDelta?.((payload as { text?: string })?.text ?? '')
        break
      case 'done':
        handlers.onDone?.(payload as unknown as DonePayload)
        break
      case 'error':
        handlers.onError?.(payload as unknown as { code: number; message: string })
        break
      default:
        break
    }
  })

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    parser.push(decoder.decode(value, { stream: true }))
  }
  parser.flush()
}

function safeParse(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}
