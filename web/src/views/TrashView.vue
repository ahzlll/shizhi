<script setup lang="ts">
// embedded：作为弹窗内容渲染时不带页面头与卡片外框
defineProps<{ embedded?: boolean }>()

import BrandLogo from '@/components/BrandLogo.vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  batchPurgeSessions,
  batchRestoreSessions,
  listTrash,
  purgeSession,
  restoreSession,
  type SessionView,
} from '@/api/chat'
import { formatTime } from '@/utils/time'

const router = useRouter()
const sessions = ref<SessionView[]>([])
const loading = ref(true)
const selected = ref(new Set<number>())
const allSelected = computed(
  () => sessions.value.length > 0 && sessions.value.every((item) => selected.value.has(item.id)),
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    sessions.value = await listTrash()
    selected.value = new Set()
  } finally {
    loading.value = false
  }
}

function toggleSelect(id: number) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function toggleAll() {
  selected.value = allSelected.value ? new Set() : new Set(sessions.value.map((item) => item.id))
}

async function restoreSelected() {
  const ids = [...selected.value]
  if (ids.length === 0) return
  const restored = await batchRestoreSessions(ids)
  ElMessage.success(`已恢复 ${restored} 个对话`)
  await load()
}

async function purgeSelected() {
  const ids = [...selected.value]
  if (ids.length === 0) return
  const confirmed = await ElMessageBox.confirm(
    `彻底删除选中的 ${ids.length} 个对话？消息会一起删掉，恢复不了。`,
    '批量彻底删除',
    { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return

  const removed = await batchPurgeSessions(ids)
  ElMessage.success(`已彻底删除 ${removed} 个对话`)
  await load()
}

async function restore(item: SessionView) {
  await restoreSession(item.id)
  ElMessage.success('已恢复到会话列表')
  await load()
}

async function purge(item: SessionView) {
  const confirmed = await ElMessageBox.confirm(
    `彻底删除「${item.title || '新对话'}」？消息会一起删掉，恢复不了。`,
    '彻底删除',
    { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return

  await purgeSession(item.id)
  ElMessage.success('已彻底删除')
  await load()
}
</script>

<template>
  <div class="page">
    <header v-if="!embedded" class="top-bar">
      <BrandLogo />
      <el-button link @click="router.push('/chat')">返回对话</el-button>
    </header>

    <main class="content" :class="{ embedded }">
      <div class="card" :class="{ embedded }">
        <h1 v-if="!embedded" class="card-title">回收站</h1>
        <p class="card-desc">删除的会话在这里保留 30 天，之后自动清理。</p>

        <p v-if="loading" class="hint">正在加载…</p>
        <p v-else-if="sessions.length === 0" class="hint">回收站是空的</p>

        <div v-if="sessions.length > 0" class="bulk">
          <el-checkbox :model-value="allSelected" @change="toggleAll">
            {{ allSelected ? '取消全选' : '全选' }}
          </el-checkbox>
          <div class="bulk-actions">
            <el-button size="small" :disabled="selected.size === 0" @click="restoreSelected">
              恢复{{ selected.size ? ` ${selected.size}` : '' }}
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="selected.size === 0"
              @click="purgeSelected"
            >
              彻底删除{{ selected.size ? ` ${selected.size}` : '' }}
            </el-button>
          </div>
        </div>

        <div v-for="item in sessions" :key="item.id" class="row">
          <el-checkbox
            :model-value="selected.has(item.id)"
            class="row-check"
            @change="toggleSelect(item.id)"
          />
          <div class="info">
            <span class="title">{{ item.title || '新对话' }}</span>
            <span class="time">删除于 {{ formatTime(item.deletedAt ?? item.createdAt) }}</span>
          </div>
          <div class="actions">
            <el-button size="small" @click="restore(item)">恢复</el-button>
            <el-button size="small" type="danger" plain @click="purge(item)">彻底删除</el-button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.top-bar {
  height: 64px;
  flex: 0 0 64px;
  padding: 0 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}




.content {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 24px 16px 60px;
}

.card {
  width: 100%;
  max-width: 640px;
  padding: 28px;
  border-radius: 18px;
}

.card-title {
  margin: 0 0 8px;
  font-size: 20px;
}

.card-desc {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--sz-ink-muted);
}

.hint {
  margin: 12px 0;
  font-size: 13px;
  color: var(--sz-ink-muted);
}

.bulk {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--sz-line);
}

.bulk-actions {
  display: flex;
  gap: 8px;
}

.row-check {
  flex: 0 0 auto;
  margin-right: 2px;
}

.bulk {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--sz-line);
}

.bulk-actions {
  display: flex;
  gap: 8px;
}

.row-check {
  flex: 0 0 auto;
  margin-right: 2px;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid var(--sz-line);
}

.info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.title {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time {
  font-size: 12px;
  color: var(--sz-ink-muted);
}

.actions {
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
}

/* 放进弹窗时由弹窗提供标题和留白，卡片本身不再画边框 */
.content.embedded {
  padding: 0;
}

.card.embedded {
  max-width: none;
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
</style>
