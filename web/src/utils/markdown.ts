import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import MarkdownIt from 'markdown-it'

const md: InstanceType<typeof MarkdownIt> = new MarkdownIt({
  // 模型输出不当作 HTML 解析；真要渲染 HTML 也过一遍 DOMPurify
  html: false,
  linkify: true,
  breaks: true,
  highlight(code, language) {
    if (language && hljs.getLanguage(language)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language }).value}</code></pre>`
      } catch {
        // 高亮失败就退回纯文本，不能让一条代码块拖垮整段渲染
      }
    }
    return `<pre class="hljs"><code>${escapeHtml(code)}</code></pre>`
  },
})

function escapeHtml(text: string): string {
  const map: Record<string, string> = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }
  return text.replace(/[&<>"']/g, (char) => map[char] ?? char)
}

export function renderMarkdown(text: string): string {
  return DOMPurify.sanitize(md.render(text))
}
