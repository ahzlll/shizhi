/** 1234 → 1.2k，用于消息下方的用量与上下文显示。 */
export function formatTokens(value: number): string {
  if (value >= 1000) {
    return `${(value / 1000).toFixed(1).replace(/\.0$/, '')}k`
  }
  return String(value)
}
