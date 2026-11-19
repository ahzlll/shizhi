<script setup lang="ts">
import BrandLogo from '@/components/BrandLogo.vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { MessageView, SessionView } from '@/api/chat'
import ChatComposer from '@/components/ChatComposer.vue'
import MessageItem from '@/components/MessageItem.vue'
import SessionSidebar from '@/components/SessionSidebar.vue'
import AppearanceView from '@/views/AppearanceView.vue'
import FeedbackView from '@/views/FeedbackView.vue'
import ProfilePage from '@/views/ProfilePage.vue'
import SettingsView from '@/views/SettingsView.vue'
import TrashView from '@/views/TrashView.vue'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { getModelConfig } from '@/api/account'
import { formatTokens } from '@/utils/format'

const auth = useAuthStore()
const chat = useChatStore()
const router = useRouter()

const scroller = ref<HTMLElement>()
// 用户往上翻之后就别再自动跟随，否则每个字都把他拽回底部
const stick = ref(true)
const drawerOpen = ref(false)
const keyword = ref('')
let searchTimer: number | undefined

const promptSets = [
  [
    '用一句话解释什么是闭包',
    'REST 和 RPC 有什么区别',
    '数据库事务的隔离级别怎么理解',
    '帮我列一个三周的复习计划',
  ],
  [
    '为什么索引能加快查询',
    'TCP 和 UDP 各自适合什么场景',
    '怎么理解幂等这个概念',
    '给我讲讲 CAP 定理',
  ],
  [
    '面向对象和函数式怎么选',
    '缓存穿透、击穿、雪崩有什么区别',
    '分布式锁一般怎么做',
    '把一个复杂问题拆成小步骤的方法',
  ],
]

const promptIndex = ref(0)
const quickPrompts = computed(() => promptSets[promptIndex.value])

function nextPrompts() {
  promptIndex.value = (promptIndex.value + 1) % promptSets.length
}

const currentTitle = computed(() => {
  const current = chat.sessions.find((item) => item.id === chat.currentId)
  return current?.title || '新对话'
})

const displaySessions = computed(() =>
  keyword.value.trim() ? chat.searchResults : chat.sessions,
)

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
    promptTokens: null,
    completionTokens: null,
    model: null,
  }
})

const ctxPercent = computed(() => Math.min(chat.context?.percent ?? 0, 100))
const modelHint = ref('')

const panel = ref<'profile' | 'appearance' | 'feedback' | 'trash' | 'settings' | null>(null)
const panelTitles = {
  profile: '个人主页',
  appearance: '外观',
  feedback: '意见反馈',
  trash: '回收站',
  settings: '模型设置',
} as const
const panelTitle = computed(() => (panel.value ? panelTitles[panel.value] : ''))
// 关掉弹窗就是清空 panel；用 computed 让 v-model 直接接上
const panelVisible = computed({
  get: () => panel.value !== null,
  set: (visible: boolean) => {
    if (!visible) panel.value = null
  },
})

/** 没有选中会话时拿不到上下文，模型名先从账号配置里取，避免显示成"默认模型"。 */
async function loadModelHint() {
  try {
    const config = await getModelConfig()
    modelHint.value = config.model ?? ''
  } catch {
    modelHint.value = ''
  }
}

const emptyText = computed(() => {
  if (keyword.value.trim()) {
    return chat.searching ? '正在搜索…' : '没有找到相关的会话'
  }
  return ''
})

onMounted(async () => {
  if (!auth.user) {
    await auth.loadMe().catch(() => undefined)
  } else {
    await auth.loadProfile().catch(() => undefined)
  }
  await chat.loadSessions()
  // 进来先停在空白态，不自动打开最近一条；历史仍然点列表才进
  chat.startNew()
  void loadModelHint()
})

watch(
  () => chat.draft?.content,
  () => {
    if (stick.value) void scrollToBottom()
  },
)
watch(
  () => chat.messages.length,
  () => {
    if (stick.value) void scrollToBottom()
  },
)

function onScroll() {
  const el = scroller.value
  if (!el) return
  stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

async function jumpToBottom() {
  stick.value = true
  await scrollToBottom()
}

async function scrollToBottom() {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
}

async function createChat() {
  await chat.newSession()
  drawerOpen.value = false
}

async function send(text: string) {
  stick.value = true
  await chat.send(text)
  await scrollToBottom()
}

async function retry(messageId: number) {
  await chat.retry(messageId)
}

async function editMessage(messageId: number, content: string) {
  await chat.editResend(messageId, content)
}

function onSearch(value: string) {
  keyword.value = value
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    void chat.search(value)
  }, 250)
}

async function selectSession(id: number) {
  await chat.selectSession(id)
  drawerOpen.value = false
  stick.value = true
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
    `把「${session.title || '新对话'}」移到回收站？30 天内可以恢复。`,
    '删除对话',
    { type: 'warning', confirmButtonText: '移到回收站', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return
  await chat.remove(session.id)
  ElMessage.success('已移到回收站')
}

async function removeSessions(ids: number[]) {
  const confirmed = await ElMessageBox.confirm(
    `把选中的 ${ids.length} 个对话移到回收站？30 天内可以恢复。`,
    '批量删除',
    { type: 'warning', confirmButtonText: '移到回收站', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return
  await chat.batchRemove(ids)
  ElMessage.success(`已把 ${ids.length} 个对话移到回收站`)
}

async function logout() {
  chat.reset()
  auth.clear()
  ElMessage.success('已退出登录')
  await router.replace({ name: 'login' })
}

function openPanel(name: 'profile' | 'appearance' | 'feedback' | 'trash' | 'settings') {
  panel.value = name
}

function onUserCommand(command: string) {
  if (command === 'logout') {
    void logout()
    return
  }
  // 这几个入口都在弹窗里处理，不跳页，对话状态和滚动位置都留着
  panel.value = command as typeof panel.value
}
</script>

<template>
  <div class="chat-page">
    <header class="top-bar">
      <div class="top-left">
        <button class="menu-btn" type="button" title="会话列表" @click="drawerOpen = !drawerOpen">
          ☰
        </button>
        <BrandLogo />
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
              <el-dropdown-item command="feedback">意见反馈</el-dropdown-item>
              <el-dropdown-item command="trash">回收站</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="body">
      <div class="sidebar-wrap" :class="{ open: drawerOpen }">
        <SessionSidebar
          :sessions="displaySessions"
          :active-id="chat.currentId"
          :loading="chat.loadingSessions"
          @create="createChat"
          @select="selectSession"
          @pin="pinSessionAction"
          @rename="renameSessionAction"
          @remove="removeSessionAction"
          @batch-remove="removeSessions"
          @search="onSearch"
        />
      </div>
      <div v-if="drawerOpen" class="drawer-mask" @click="drawerOpen = false"></div>

      <main class="main">
        <div class="main-head">
          <h2 class="title">{{ currentTitle }}</h2>
        </div>

        <div ref="scroller" class="messages" @scroll.passive="onScroll">
          <button v-if="chat.hasMore" class="load-more" type="button" @click="chat.loadHistory">
            加载更早的消息
          </button>

          <p v-if="emptyText" class="empty">{{ emptyText }}</p>

          <MessageItem
            v-for="message in chat.messages"
            :key="message.id"
            :message="message"
            @retry="retry"
            @edit="editMessage"
          />

          <MessageItem
            v-if="draftMessage"
            :message="draftMessage"
            :streaming="chat.streaming"
            @retry="retry"
          />
        </div>

        <button v-if="!stick" class="to-bottom" type="button" @click="jumpToBottom">
          回到最新
        </button>

        <div class="composer-wrap">
          <div v-if="chat.messages.length === 0 && !draftMessage" class="quick">
            <div class="quick-head">
              <p class="quick-title">试试这些问题</p>
              <button type="button" class="quick-refresh" @click="nextPrompts">换一批</button>
            </div>
            <div class="quick-list">
              <button
                v-for="prompt in quickPrompts"
                :key="prompt"
                type="button"
                class="quick-item"
                @click="send(prompt)"
              >
                {{ prompt }}
              </button>
            </div>
          </div>
          <div class="composer-right">
            <div class="model-bar">
              <button class="model-chip" type="button" @click="openPanel('settings')">
                {{ chat.context?.model || modelHint || '默认模型' }}
                <span v-if="chat.context?.custom" class="model-badge">自带</span>
              </button>
              <span class="ctx-text">
                上下文 {{ formatTokens(chat.context?.usedTokens ?? 0) }} /
                {{ formatTokens(chat.context?.contextWindow ?? 32768) }}
                <span class="ctx-bar"><i :style="{ width: ctxPercent + '%' }"></i></span>
                {{ ctxPercent.toFixed(1) }}%
              </span>
            </div>
            <ChatComposer :streaming="chat.streaming" @send="send" @stop="chat.stop" />
          </div>
        </div>
      </main>
    </div>

    <el-dialog
      v-model="panelVisible"
      :title="panelTitle"
      width="760px"
      class="panel-dialog"
      append-to-body
      destroy-on-close
      align-center
    >
      <ProfilePage v-if="panel === 'profile'" embedded />
      <AppearanceView v-else-if="panel === 'appearance'" embedded />
      <FeedbackView v-else-if="panel === 'feedback'" embedded />
      <TrashView v-else-if="panel === 'trash'" embedded />
      <SettingsView v-else-if="panel === 'settings'" embedded />
      <SettingsView v-else-if="panel === 'settings'" embedded />
    </el-dialog>
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

.top-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.menu-btn {
  display: none;
  border: none;
  background: none;
  font-size: 18px;
  cursor: pointer;
  padding: 4px 6px;
  color: var(--sz-ink-muted);
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

.sidebar-wrap {
  flex: 0 0 auto;
}

.main {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.to-bottom {
  position: absolute;
  right: 24px;
  bottom: 96px;
  z-index: 5;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--sz-line);
  background-color: var(--sz-surface);
  color: var(--sz-brand-deep);
  font-size: 12px;
  cursor: pointer;
  box-shadow: var(--sz-shadow-soft);
}

.main-head {
  width: 100%;
  max-width: 792px;
  margin: 0 auto;
  padding: 18px 0 10px;
}

.title {
  margin: 0;
  padding: 0 16px;
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px 16px 20px;
  display: flex;
  flex-direction: column;
  gap: var(--sz-msg-gap, 22px);
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
  width: 100%;
  max-width: 792px;
  margin: 0 auto;
  padding: 0 16px 18px;
}

/* 与上方内容列同宽并居中对齐 */
.composer-right {
  width: 100%;
}

/* 输入框上方：当前模型 + 上下文占用，点模型名直接去设置 */
.model-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 4px 8px;
  font-size: 12px;
  color: var(--sz-ink-muted);
}

.model-chip {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  cursor: pointer;
  color: var(--sz-brand-deep);
}

.model-chip:hover {
  text-decoration: underline;
}

.model-badge {
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 4px;
  background-color: var(--sz-brand-soft);
  font-size: 11px;
}

.ctx-text {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.ctx-bar {
  display: inline-block;
  width: 56px;
  height: 4px;
  border-radius: 999px;
  background-color: var(--sz-line);
  overflow: hidden;
}

.ctx-bar i {
  display: block;
  height: 100%;
  background-color: var(--sz-brand);
  transition: width 0.2s ease;
}

/* 输入框上方：当前模型 + 上下文占用，点模型名直接去设置 */
.model-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 4px 8px;
  font-size: 12px;
  color: var(--sz-ink-muted);
}

.model-chip {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  cursor: pointer;
  color: var(--sz-brand-deep);
}

.model-chip:hover {
  text-decoration: underline;
}

.model-badge {
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 4px;
  background-color: var(--sz-brand-soft);
  font-size: 11px;
}

.ctx-text {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.ctx-bar {
  display: inline-block;
  width: 56px;
  height: 4px;
  border-radius: 999px;
  background-color: var(--sz-line);
  overflow: hidden;
}

.ctx-bar i {
  display: block;
  height: 100%;
  background-color: var(--sz-brand);
  transition: width 0.2s ease;
}

.quick {
  width: 100%;
  margin: 0 0 10px;
}

.quick-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.quick-refresh {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  cursor: pointer;
  color: var(--sz-brand-deep);
}

.quick-title {
  margin: 0;
  font-size: 12px;
  color: var(--sz-ink-muted);
}

.quick-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.quick-item {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--sz-line);
  background-color: var(--sz-surface);
  color: var(--sz-ink);
  font-size: 13px;
  cursor: pointer;
}

.quick-item:hover {
  border-color: var(--sz-brand);
  color: var(--sz-brand-deep);
}

.drawer-mask {
  display: none;
}

@media (max-width: 860px) {
  .top-bar {
    padding: 0 16px;
  }

  .menu-btn {
    display: inline-block;
  }

  .sidebar-wrap {
    position: fixed;
    top: 0;
    left: 0;
    z-index: 30;
    height: 100%;
    width: 280px;
    transform: translateX(-100%);
    transition: transform 0.2s ease;
    background-color: var(--sz-surface);
    box-shadow: var(--sz-shadow);
  }

  .sidebar-wrap.open {
    transform: translateX(0);
  }

  .drawer-mask {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 20;
    background-color: rgba(0, 0, 0, 0.32);
  }

  .messages {
    padding: 8px 14px 16px;
  }

  .main-head {
    padding: 12px 0 6px;
  }

  .composer-wrap {
    padding: 0 10px 12px;
  }

  /* 窄屏上侧栏抽屉化后，输入区撑满可用宽度 */
  .composer-right,
  .quick {
    width: 100%;
  }
}
</style>
