<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

onMounted(async () => {
  if (!auth.user) {
    await auth.loadMe().catch(() => undefined)
  }
})

async function logout() {
  auth.clear()
  ElMessage.success('已退出登录')
  await router.replace({ name: 'login' })
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
        <span class="user-name">{{ auth.displayName }}</span>
        <el-button link @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="empty-state">
      <p class="empty-hint">还没有对话</p>
    </main>
  </div>
</template>

<style scoped>
.chat-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.top-bar {
  height: 64px;
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

.empty-state {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 24px;
}

.empty-hint {
  margin: 0;
  font-size: 14px;
  letter-spacing: 1px;
}
</style>
