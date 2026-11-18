<script setup lang="ts">
import { computed } from 'vue'

import type { MessageView } from '@/api/chat'
import { renderMarkdown } from '@/utils/markdown'
import { formatTime } from '@/utils/time'

const props = defineProps<{
  message: MessageView
  streaming?: boolean
}>()

const emit = defineEmits<{ retry: [id: number] }>()

const isUser = computed(() => props.message.role === 'user')
const html = computed(() => (isUser.value ? '' : renderMarkdown(props.message.content)))
const interrupted = computed(() => props.message.streamState === 2)
const failed = computed(() => props.message.streamState === 3)
const canRetry = computed(
  () => props.message.id > 0 && !props.streaming && (interrupted.value || failed.value),
)
// 已完成的回答也能重说一遍：答得不满意时不必重开一个会话
const canRegenerate = computed(
  () =>
    !isUser.value &&
    props.message.id > 0 &&
    !props.streaming &&
    props.message.streamState === 1,
)
// 失败且一个字都没产出时只留状态行，否则会出现一个空白气泡
const showBubble = computed(() => props.streaming || props.message.content.trim().length > 0)
</script>

<template>
  <div class="message" :class="isUser ? 'is-user' : 'is-assistant'">
    <div v-if="showBubble" class="bubble">
      <div v-if="isUser" class="plain">{{ message.content }}</div>
      <div v-else class="markdown" v-html="html"></div>
      <span v-if="streaming" class="caret"></span>
    </div>

    <div class="meta">
      <span class="time">{{ formatTime(message.createdAt) }}</span>
      <template v-if="interrupted">
        <span class="badge">已中断</span>
        <button v-if="canRetry" class="retry" type="button" @click="emit('retry', message.id)">
          重新回答
        </button>
      </template>
      <template v-else-if="failed">
        <span class="badge is-error">没答出来</span>
        <button v-if="canRetry" class="retry" type="button" @click="emit('retry', message.id)">
          再试一次
        </button>
      </template>
      <button
        v-else-if="canRegenerate"
        class="retry regen"
        type="button"
        @click="emit('retry', message.id)"
      >
        重新生成
      </button>
    </div>
  </div>
</template>

<style scoped>
.message {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 720px;
}

.is-user {
  align-self: flex-end;
  align-items: flex-end;
}

.is-assistant {
  align-self: flex-start;
  align-items: flex-start;
}

.bubble {
  padding: 12px 16px;
  border-radius: 16px;
  line-height: 1.75;
  word-break: break-word;
}

.is-user .bubble {
  border-bottom-right-radius: 4px;
}

.is-assistant .bubble {
  border-bottom-left-radius: 4px;
}

.plain {
  white-space: pre-wrap;
}

.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
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

.retry {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  cursor: pointer;
  text-decoration: underline;
}

.regen {
  opacity: 0;
  transition: opacity 0.15s ease;
}

.message:hover .regen {
  opacity: 1;
}

.caret {
  display: inline-block;
  width: 6px;
  height: 16px;
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
