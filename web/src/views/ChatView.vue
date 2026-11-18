<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import type { MessageView, SessionView } from '@/api/chat'
import ChatComposer from '@/components/ChatComposer.vue'
import MessageItem from '@/components/MessageItem.vue'
import SessionSidebar from '@/components/SessionSidebar.vue'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'

const auth = useAuthStore()
const chat = useChatStore()
const router = useRouter()

const scroller = ref<HTMLElement>()

const currentTitle = computed(() => {
  const current = chat.sessions.find((item) => item.id === chat.currentId)
  return current?.title || '新对话'
})

const draftMessage = computed<MessageView | null>(() => {
  if (!chat.draft) return null
  return {
    id: chat.draft.id ?? 0,
    role: 'assistant',
    content: chat.draft.content,
    streamState: chat.draft.state,
    interruptReason: null,
    supersededBy: null,
    refNoteIds: null,
    refConceptIds: null,
    createdAt: new Date().toISOString(),
  }
})

onMounted(async () => {
  if (!auth.user) {
    await auth.loadMe().catch(() => undefined)
  } else {
    await auth.loadProfile().catch(() => undefined)
  }
  await chat.loadSessions()
  if (chat.sessions.length > 0) {
    await chat.selectSession(chat.sessions[0].id)
  }
  await scrollToBottom()
})

watch(
  () => chat.draft?.content,
  () => void scrollToBottom(),
)
watch(
  () => chat.messages.length,
  () => void scrollToBottom(),
)

async function scrollToBottom() {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
}

async function createChat() {
  await chat.newSession()
}

async function send(text: string) {
  await chat.send(text)
}

async function retry(messageId: number) {
  await chat.retry(messageId)
}

async function pinSessionAction(session: SessionView, pinned: boolean) {
  await chat.pin(session.id, pinned)
}

async function renameSessionAction(session: SessionView) {
  const result = await ElMessageBox.prompt('给这个对话起个名字', '重命名', {
    inputValue: session.title ?? '',
    inputValidator: (value: string) => (value && value.trim() ? true : '标题不能为空'),
    confirmButtonText: '保存',
    cancelButtonText: '取消',
  }).catch(() => null)
  if (!result?.value) return
  await chat.rename(session.id, result.value.trim())
}

async function removeSessionAction(session: SessionView) {
  const confirmed = await ElMessageBox.confirm(
    `删除「${session.title || '新对话'}」和它的全部消息？删除后无法恢复。`,
    '删除对话',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return
  await chat.remove(session.id)
  ElMessage.success('已删除')
}

async function logout() {
  chat.reset()
  auth.clear()
  ElMessage.success('已退出登录')
  await router.replace({ name: 'login' })
}

function onUserCommand(command: string) {
  if (command === 'logout') {
    void logout()
    return
  }
  void router.push(`/${command}`)
}
</script>

<template>
  <div class="chat-page">
    <header class="top-bar">
      <div class="brand">
        <span class="brand-mark">拾</span>
        <span class="brand-name">拾知成图</span>
      </div>
      <div class="top-actions">
        <el-dropdown trigger="click" @command="onUserCommand">
          <button class="user-chip" type="button">
            <img v-if="auth.avatar" :src="auth.avatar" class="avatar" alt="" />
            <span v-else class="avatar avatar-fallback">{{ auth.initial }}</span>
            <span class="user-name">{{ auth.displayName }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人主页</el-dropdown-item>
              <el-dropdown-item command="appearance">外观</el-dropdown-item>
              <el-dropdown-item command="settings">模型设置</el-dropdown-item>
              <el-dropdown-item command="feedback">意见反馈</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="body">
      <SessionSidebar
        :sessions="chat.sessions"
        :active-id="chat.currentId"
        :loading="chat.loadingSessions"
        @create="createChat"
        @select="chat.selectSession"
        @pin="pinSessionAction"
        @rename="renameSessionAction"
        @remove="removeSessionAction"
      />

      <main class="main">
        <div class="main-head">
          <h2 class="title">{{ currentTitle }}</h2>
        </div>

        <div ref="scroller" class="messages">
          <button v-if="chat.hasMore" class="load-more" type="button" @click="chat.loadHistory">
            加载更早的消息
          </button>

          <p v-if="chat.messages.length === 0 && !draftMessage" class="empty">
            问点什么，答案会留在这里。
          </p>

          <MessageItem
            v-for="message in chat.messages"
            :key="message.id"
            :message="message"
            @retry="retry"
          />

          <MessageItem
            v-if="draftMessage"
            :message="draftMessage"
            :streaming="chat.streaming"
            @retry="retry"
          />
        </div>

        <div class="composer-wrap">
          <ChatComposer :streaming="chat.streaming" @send="send" @stop="chat.stop" />
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.top-bar {
  height: 64px;
  flex: 0 0 64px;
  padding: 0 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-weight: 600;
}

.brand-name {
  font-size: 16px;
  letter-spacing: 1px;
}

.top-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 4px;
  border: none;
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
}

.user-chip:hover {
  background-color: var(--sz-hover);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  object-fit: cover;
}

.avatar-fallback {
  display: grid;
  place-items: center;
  background-color: var(--sz-brand-soft);
  color: var(--sz-brand-deep);
  font-size: 13px;
  font-weight: 600;
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.main-head {
  padding: 18px 28px 10px;
}

.title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px 28px 20px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.empty {
  margin: auto;
  font-size: 14px;
}

.load-more {
  align-self: center;
  border: none;
  background: none;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 10px;
}

.composer-wrap {
  padding: 0 20px 18px;
}

@media (max-width: 860px) {
  .top-bar {
    padding: 0 16px;
  }

  .body {
    flex-direction: column;
  }

  .messages {
    padding: 8px 14px 16px;
  }

  .main-head {
    padding: 12px 16px 6px;
  }

  .composer-wrap {
    padding: 0 10px 12px;
  }
}
</style>
