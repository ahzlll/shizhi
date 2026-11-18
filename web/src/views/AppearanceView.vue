<script setup lang="ts">
import { useRouter } from 'vue-router'

import { ACCENTS, useThemeStore, type ThemeAccent, type ThemeMode } from '@/stores/theme'

const router = useRouter()
const theme = useThemeStore()

const modes: { value: ThemeMode; label: string; desc: string }[] = [
  { value: 'light', label: '浅色', desc: '白底深字，白天看更清楚' },
  { value: 'dark', label: '深色', desc: '暗底浅字，晚上不刺眼' },
]

function pickAccent(value: ThemeAccent) {
  theme.setAccent(value)
}
</script>

<template>
  <div class="page">
    <header class="top-bar">
      <div class="brand">
        <span class="brand-mark">拾</span>
        <span class="brand-name">拾知成图</span>
      </div>
      <el-button link @click="router.push('/chat')">返回对话</el-button>
    </header>

    <main class="content">
      <div class="card">
        <h1 class="card-title">外观</h1>
        <p class="card-desc">只影响这台设备的显示，换设备需要重新选一次。</p>

        <section class="section">
          <h2 class="section-title">明暗</h2>
          <div class="mode-list">
            <button
              v-for="item in modes"
              :key="item.value"
              type="button"
              class="mode-item"
              :class="{ active: theme.mode === item.value }"
              @click="theme.setMode(item.value)"
            >
              <span class="mode-label">{{ item.label }}</span>
              <span class="mode-desc">{{ item.desc }}</span>
            </button>
          </div>
        </section>

        <section class="section">
          <h2 class="section-title">主题色</h2>
          <div class="accent-list">
            <button
              v-for="item in ACCENTS"
              :key="item.value"
              type="button"
              class="accent-item"
              :class="{ active: theme.accent === item.value }"
              @click="pickAccent(item.value)"
            >
              <span class="accent-dot" :style="{ backgroundColor: item.color }"></span>
              {{ item.label }}
            </button>
          </div>
        </section>
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

.content {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 24px 16px 60px;
}

.card {
  width: 100%;
  max-width: 560px;
  padding: 28px;
  border-radius: 18px;
}

.card-title {
  margin: 0 0 8px;
  font-size: 20px;
}

.card-desc {
  margin: 0 0 22px;
  font-size: 13px;
}

.section + .section {
  margin-top: 26px;
}

.section-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
}

.mode-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.mode-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px;
  border-radius: 12px;
  border: 1px solid var(--sz-line);
  background-color: var(--sz-surface);
  text-align: left;
  cursor: pointer;
}

.mode-item.active {
  border-color: var(--sz-brand);
  background-color: var(--sz-brand-soft);
}

.mode-label {
  font-size: 14px;
  font-weight: 600;
}

.mode-desc {
  font-size: 12px;
  color: var(--sz-ink-muted);
}
</style>
