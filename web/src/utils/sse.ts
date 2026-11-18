export interface SseEvent {
  event: string
  data: string
}

/**
 * 按空行切分事件块。半包（一个事件被切在两次 read 之间）是常态，
 * 所以没闭合的尾巴必须留在缓冲区，等下一块拼上再解析。
 */
export function createSseParser(onEvent: (event: SseEvent) => void) {
  let buffer = ''

  function push(chunk: string) {
    buffer += chunk.replace(/\r\n/g, '\n')
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''
    for (const block of blocks) {
      const parsed = parseBlock(block)
      if (parsed) onEvent(parsed)
    }
  }

  function flush() {
    const rest = buffer.trim()
    buffer = ''
    if (!rest) return
    const parsed = parseBlock(rest)
    if (parsed) onEvent(parsed)
  }

  return { push, flush }
}

function parseBlock(block: string): SseEvent | null {
  let event = 'message'
  const data: string[] = []

  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      data.push(line.slice('data:'.length).replace(/^ /, ''))
    }
  }

  if (data.length === 0) return null
  return { event, data: data.join('\n') }
}
