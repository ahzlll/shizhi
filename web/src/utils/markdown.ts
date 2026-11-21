import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import MarkdownIt from 'markdown-it'
import texmath from 'markdown-it-texmath'
import katex from 'katex'

import 'katex/dist/katex.min.css'

/** 超过这么多行的代码块默认折叠，长示例不会把一条回答撑成几屏。 */
const COLLAPSE_LINES = 18

const md: InstanceType<typeof MarkdownIt> = new MarkdownIt({
  // 模型输出不当作 HTML 解析；真要渲染 HTML 也过一遍 DOMPurify
  html: false,
  linkify: true,
  breaks: true,
  highlight(code, language) {
    const lang = (language || '').trim().toLowerCase()
    const body =
      lang && hljs.getLanguage(lang)
        ? highlightSafely(code, lang)
        : escapeHtml(code)

    const lines = code.split('\n').length
    const collapsible = lines > COLLAPSE_LINES
    const head = `<div class="code-head"><span class="code-lang">${lang || 'text'}</span>`
      + '<button type="button" class="code-copy" data-copy-code>复制</button></div>'
    const cls = collapsible ? 'code-body is-collapsed' : 'code-body'
    const toggle = collapsible
      ? '<button type="button" class="code-toggle" data-toggle-code>展开全部</button>'
      : ''

    return `<div class="code-block">${head}<pre class="${cls}"><code class="hljs">${body}</code></pre>${toggle}</div>`
  },
})

md.use(texmath, { engine: katex, delimiters: 'dollars', katexOptions: { throwOnError: false } })

// 窄屏放不下宽表格，外面套一层可横向滚动的容器
const defaultTableOpen =
  md.renderer.rules.table_open ?? ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.table_open = (tokens, idx, options, env, self) =>
  `<div class="table-wrap">${defaultTableOpen(tokens, idx, options, env, self)}`
const defaultTableClose =
  md.renderer.rules.table_close ?? ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.table_close = (tokens, idx, options, env, self) =>
  `${defaultTableClose(tokens, idx, options, env, self)}</div>`

// 外链新窗口打开，别把对话页顶掉
const defaultLinkOpen =
  md.renderer.rules.link_open ?? ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

function highlightSafely(code: string, language: string): string {
  try {
    return hljs.highlight(code, { language }).value
  } catch {
    // 高亮失败就退回纯文本，不能让一条代码块拖垮整段渲染
    return escapeHtml(code)
  }
}

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
  return DOMPurify.sanitize(md.render(text), { ADD_ATTR: ['target'] })
}
