<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  streaming: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{ send: [text: string]; stop: [] }>()

const text = ref('')

function submit() {
  if (props.streaming || props.disabled) return
  const value = text.value.trim()
  if (!value) return
  emit('send', value)
  text.value = ''
}

function onKeydown(event: KeyboardEvent) {
  // 输入法选词时的回车不算提交，否则中文输入法下每选一次词就发一条
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  submit()
}
</script>

<template>
  <div class="composer">
    <el-input
      v-model="text"
      type="textarea"
      :autosize="{ minRows: 1, maxRows: 5 }"
      resize="none"
      placeholder="问点什么，回车发送，Shift + 回车换行"
      :disabled="disabled"
      @keydown="onKeydown"
    />
    <el-button v-if="streaming" class="action" @click="emit('stop')">停止</el-button>
    <el-button v-else class="action" type="primary" :disabled="disabled || !text.trim()" @click="submit">
      发送
    </el-button>
  </div>
</template>

<style scoped>
.composer {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 16px;
}

.composer :deep(.el-textarea__inner) {
  padding: 8px 4px;
  box-shadow: none;
  background: transparent;
  font-size: 14px;
  line-height: 1.7;
}

.composer :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}

.action {
  height: 36px;
  min-width: 72px;
}
</style>
