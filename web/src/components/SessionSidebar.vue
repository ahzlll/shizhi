<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import type { SessionView } from '@/api/chat'
import { formatTime } from '@/utils/time'

const props = defineProps<{
  sessions: SessionView[]
  activeId: number | null
  loading: boolean
}>()

const emit = defineEmits<{
  create: []
  select: [id: number]
  pin: [session: SessionView, pinned: boolean]
  rename: [session: SessionView]
  remove: [session: SessionView]
}>()

const items = computed(() => props.sessions)
const menu = ref<{ session: SessionView; x: number; y: number } | null>(null)

function openMenu(session: SessionView, event: MouseEvent) {
  // 靠近右/下边缘时往回挪，别让菜单跑到视口外
  const x = Math.min(event.clientX, window.innerWidth - 170)
  const y = Math.min(event.clientY, window.innerHeight - 150)
  menu.value = { session, x, y }
}

function closeMenu() {
  menu.value = null
}

function pick(action: 'pin' | 'rename' | 'remove') {
  const target = menu.value?.session
  if (!target) return
  closeMenu()
  if (action === 'pin') emit('pin', target, !target.pinned)
  if (action === 'rename') emit('rename', target)
  if (action === 'remove') emit('remove', target)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeMenu()
}

onMounted(() => {
  window.addEventListener('click', closeMenu)
  window.addEventListener('scroll', closeMenu, true)
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', closeMenu)
  window.removeEventListener('scroll', closeMenu, true)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <aside class="sidebar">
    <button class="new-chat" type="button" @click="emit('create')">新建对话</button>

    <div class="list">
      <p v-if="loading && items.length === 0" class="hint">正在加载…</p>
      <p v-else-if="items.length === 0" class="hint">还没有对话</p>

      <div
        v-for="item in items"
        :key="item.id"
        class="row"
        @contextmenu.prevent="openMenu(item, $event)"
      >
        <button
          type="button"
          class="item"
          :class="{ active: item.id === activeId }"
          @click="emit('select', item.id)"
        >
          <span class="title">
            <span v-if="item.pinned" class="pin">置顶</span>
            {{ item.title || '新对话' }}
          </span>
          <span class="time">{{ formatTime(item.lastActiveAt) }}</span>
        </button>
        <button type="button" class="more" title="更多" @click.stop="openMenu(item, $event)">
          ⋯
        </button>
      </div>
    </div>

    <Teleport to="body">
      <div
        v-if="menu"
        class="menu"
        :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
        @click.stop
      >
        <button type="button" class="menu-item" @click.stop="pick('pin')">
          {{ menu.session.pinned ? '取消置顶' : '置顶' }}
        </button>
        <button type="button" class="menu-item" @click.stop="pick('rename')">重命名</button>
        <button type="button" class="menu-item danger" @click.stop="pick('remove')">删除</button>
      </div>
    </Teleport>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 264px;
  flex: 0 0 264px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 14px;
  overflow: hidden;
}

.new-chat {
  height: 40px;
  border-radius: 10px;
  border: 1px dashed transparent;
  font-size: 14px;
  cursor: pointer;
}

.list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.hint {
  margin: 8px 4px;
  font-size: 13px;
}

.row {
  position: relative;
}

.item {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 9px 30px 9px 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.item.active {
  background-color: var(--sz-brand-soft, #eaf8f1);
}

.title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pin {
  display: inline-block;
  margin-right: 4px;
  padding: 0 4px;
  border-radius: 4px;
  font-size: 10px;
  color: var(--sz-brand-deep, #2f8f66);
  background-color: rgba(82, 183, 136, 0.16);
}

.time {
  font-size: 11px;
}

.more {
  position: absolute;
  top: 8px;
  right: 6px;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  line-height: 1;
  opacity: 0;
}

.row:hover .more,
.row:focus-within .more {
  opacity: 1;
}

.menu {
  position: fixed;
  z-index: 3000;
  min-width: 140px;
  padding: 6px;
  border-radius: 10px;
  background-color: #ffffff;
  border: 1px solid var(--sz-line, #e2efe7);
  box-shadow: 0 12px 28px rgba(31, 45, 40, 0.14);
}

.menu-item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 6px;
  background: none;
  text-align: left;
  font-size: 13px;
  cursor: pointer;
}

.menu-item:hover {
  background-color: var(--sz-brand-soft, #eaf8f1);
}

.menu-item.danger {
  color: #c05a4e;
}

@media (max-width: 860px) {
  .sidebar {
    width: 100%;
    flex: 0 0 auto;
    max-height: 180px;
    border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  }

  .more {
    opacity: 1;
  }
}
</style>
