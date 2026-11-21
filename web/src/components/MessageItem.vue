<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, EditPen, RefreshRight } from '@element-plus/icons-vue'

import type { MessageView } from '@/api/chat'
import { formatTokens } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'
import { formatTime } from '@/utils/time'

const props = defineProps<{
  message: MessageView
  streaming?: boolean
}>()

const emit = defineEmits<{
  retry: [id: number]
  edit: [id: number, content: string]
}>()

const isUser = computed(() => props.message.role === 'user')
const html = computed(() => (isUser.value ? '' : renderMarkdown(props.message.content)))
const interrupted = computed(() => props.message.streamState === 2)
const failed = computed(() => props.message.streamState === 3)
const canRetry = computed(
  () => props.message.id > 0 && !props.streaming && (interrupted.value || failed.value),
)
const canRegenerate = computed(
  () => !isUser.value && props.message.id > 0 && !props.streaming && props.message.streamState === 1,
)
// 有内容才渲染正文；生成中即使还没出字也保留容器，好让光标有地方待
const showContent = computed(() => props.streaming || props.message.content.trim().length > 0)

// 只统计这条回答本身的用量，不含重新送进去的历史上下文
const usageText = computed(() => {
  const completion = props.message.completionTokens ?? 0
  return completion > 0 ? formatTokens(completion) : ''
})

const editing = ref(false)
const draft = ref('')
const editor = ref<{ focus: () => void }>()

async function startEdit() {
  if (props.streaming) return
  draft.value = props.message.content
  editing.value = true
  await nextTick()
  editor.value?.focus()
}

function cancelEdit() {
  editing.value = false
}

async function saveEdit() {
  const text = draft.value.trim()
  if (!text) return
  // 保存后这条提问之后的问答会被删掉，误点无法恢复，先确认
  const confirmed = await ElMessageBox.confirm(
    '修改后会重新回答，这条提问之后的问答将被删除，无法恢复。',
    '编辑并重新发送',
    { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return
  editing.value = false
  emit('edit', props.message.id, text)
}

/** 已完成的回答要覆盖旧的，先确认；中断/失败的没什么可丢，直接重试。 */
async function regenerate() {
  if (canRetry.value) {
    emit('retry', props.message.id)
    return
  }
  const confirmed = await ElMessageBox.confirm(
    '重新生成会替换这条回答，当前内容将不再显示。',
    '重新生成',
    { type: 'warning', confirmButtonText: '重新生成', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (confirmed) emit('retry', props.message.id)
}

async function copyContent(button?: HTMLElement) {
  await copyText(props.message.content, button)
}

async function copyText(text: string, button?: HTMLElement) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
    if (button) flash(button)
  } catch {
    ElMessage.error('复制失败，请手动选择')
  }
}

function flash(button: HTMLElement) {
  const original = button.textContent ?? ''
  button.textContent = '已复制'
  window.setTimeout(() => {
    button.textContent = original
  }, 1500)
}

/** 代码块的复制与折叠按钮由 markdown 渲染出来，这里统一做事件委托。 */
function onMarkdownClick(event: MouseEvent) {
  const target = event.target as HTMLElement
  const copyButton = target.closest<HTMLElement>('[data-copy-code]')
  if (copyButton) {
    const code = copyButton.closest('.code-block')?.querySelector('code')?.textContent ?? ''
    void copyText(code, copyButton)
    return
  }

  const toggle = target.closest<HTMLElement>('[data-toggle-code]')
  if (toggle) {
    const body = toggle.closest('.code-block')?.querySelector('.code-body')
    if (!body) return
    const collapsed = body.classList.toggle('is-collapsed')
    toggle.textContent = collapsed ? '展开全部' : '收起'
  }
}
</script>

<template>
  <div class="message" :class="isUser ? 'is-user' : 'is-assistant'">
    <template v-if="isUser">
      <div v-if="!editing" class="ask">{{ message.content }}</div>
      <div v-else class="editor">
        <el-input
          ref="editor"
          v-model="draft"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 8 }"
          resize="none"
          @keydown.esc="cancelEdit"
        />
        <div class="editor-actions">
          <el-button size="small" @click="cancelEdit">取消</el-button>
          <el-button size="small" type="primary" :disabled="!draft.trim()" @click="saveEdit">
            发送
          </el-button>
        </div>
      </div>
    </template>

    <div v-else class="answer" :class="{ 'is-filled': showContent }">
      <div v-if="showContent" class="markdown" v-html="html" @click="onMarkdownClick"></div>
      <span v-if="streaming" class="caret"></span>
    </div>

    <div class="meta">
      <span class="time">{{ formatTime(message.createdAt) }}</span>

      <template v-if="isUser">
        <template v-if="message.id > 0 && !editing && !streaming">
          <el-tooltip content="编辑" placement="bottom" :offset="8" effect="dark">
            <button class="icon-btn" type="button" aria-label="编辑" @click="startEdit">
              <el-icon><EditPen /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip content="复制" placement="bottom" :offset="8" effect="dark">
            <button class="icon-btn" type="button" aria-label="复制" @click="copyContent()">
              <el-icon><CopyDocument /></el-icon>
            </button>
          </el-tooltip>
        </template>
      </template>

      <template v-else>
        <el-tooltip v-if="message.id > 0 && !streaming" content="复制" placement="bottom" :offset="8" effect="dark">
          <button class="icon-btn" type="button" aria-label="复制" @click="copyContent()">
            <el-icon><CopyDocument /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip
          v-if="canRetry || canRegenerate"
          :content="canRetry ? '重新回答' : '重新生成'"
          placement="bottom" :offset="8"
          effect="dark"
        >
          <button class="icon-btn" type="button" @click="regenerate">
            <el-icon><RefreshRight /></el-icon>
          </button>
        </el-tooltip>

        <span v-if="interrupted" class="badge">已中断</span>
        <span v-else-if="failed" class="badge is-error">没答出来</span>

        <span v-if="usageText" class="usage">共消耗 {{ usageText }}</span>
        <span v-if="message.model" class="model">{{ message.model }}</span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.message {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
  /* 操作提示挂在按钮下方，这里留出它的高度，免得压住下一条消息的正文 */
  padding-bottom: 16px;
}

/* 提问用一条竖线区分，和回答同处一列 */
.is-user {
  align-self: stretch;
  align-items: flex-start;
}

/* 回答不再套气泡，减少一层框，长文更好读 */
.is-assistant {
  align-self: stretch;
  align-items: flex-start;
}

.ask {
  padding: 2px 0 2px 12px;
  border-left: 3px solid var(--sz-brand);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sz-ink);
}

.answer {
  width: 100%;
  font-size: var(--sz-msg-font, 14px);
  line-height: var(--sz-msg-line, 1.75);
}

/* 回答是整块白底，和页面背景分开 */
.answer.is-filled {
  padding: 14px 18px;
  border-radius: 14px;
  background-color: var(--sz-surface);
  border: 1px solid var(--sz-line);
  box-shadow: var(--sz-shadow-soft);
}

.editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  border: none;
  background: none;
  padding: 0;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  color: var(--sz-ink-muted);
}

.icon-btn:hover {
  color: var(--sz-brand-deep);
}

.usage,
.model {
  font-size: 12px;
  color: var(--sz-ink-muted);
}

.model {
  padding-left: 2px;
}

.badge {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
}

.badge.is-error {
  color: #b4544a;
  background-color: rgba(196, 92, 78, 0.1);
}

.caret {
  display: inline-block;
  width: 6px;
  height: 15px;
  margin-left: 2px;
  vertical-align: -2px;
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}
</style>
