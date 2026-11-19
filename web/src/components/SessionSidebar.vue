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
  batchRemove: [ids: number[]]
  search: [keyword: string]
}>()

const items = computed(() => props.sessions)
const menu = ref<{ session: SessionView; x: number; y: number } | null>(null)
const keyword = ref('')

const managing = ref(false)
const selected = ref(new Set<number>())
const allSelected = computed(
  () => items.value.length > 0 && items.value.every((item) => selected.value.has(item.id)),
)

function toggleManaging() {
  managing.value = !managing.value
  selected.value = new Set()
}

function toggleSelect(id: number) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function toggleAll() {
  selected.value = allSelected.value ? new Set() : new Set(items.value.map((item) => item.id))
}

function onItemClick(item: SessionView) {
  if (managing.value) toggleSelect(item.id)
  else emit('select', item.id)
}

function removeSelected() {
  if (selected.value.size === 0) return
  emit('batchRemove', [...selected.value])
  selected.value = new Set()
  managing.value = false
}

function openMenu(session: SessionView, event: MouseEvent) {
  // 靠近右/下边缘时往回挪，别让菜单跑到视口外
  const x = Math.min(event.clientX, window.innerWidth - 170)
  const y = Math.min(event.clientY, window.innerHeight - 150)
  menu.value = { session, x, y }
}

function closeMenu() {
  menu.value = null
}

function pick(action: 'pin' | 'rename' | 'remove' | 'manage') {
  const target = menu.value?.session
  if (!target) return
  closeMenu()
  if (action === 'pin') emit('pin', target, !target.pinned)
  if (action === 'rename') emit('rename', target)
  if (action === 'remove') emit('remove', target)
  if (action === 'manage') toggleManaging()
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

    <el-input
      v-model="keyword"
      class="search"
      placeholder="搜索会话或消息"
      clearable
      @input="emit('search', keyword)"
    />

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
          :class="{
            active: item.id === activeId,
            selected: managing && selected.has(item.id),
            managing,
          }"
          @click="onItemClick(item)"
        >
          <span v-if="managing" class="check" :class="{ on: selected.has(item.id) }"></span>
          <span class="item-text">
            <span class="title">
              <span v-if="item.pinned" class="pin">置顶</span>
              {{ item.title || '新对话' }}
            </span>
            <span class="time">{{ formatTime(item.lastActiveAt) }}</span>
          </span>
        </button>
        <button
          v-if="!managing"
          type="button"
          class="more"
          title="更多"
          @click.stop="openMenu(item, $event)"
        >
          ⋯
        </button>
      </div>
    </div>

    <footer v-if="managing" class="sidebar-foot">
      <button type="button" class="foot-btn" @click="toggleAll">
        {{ allSelected ? '取消全选' : '全选' }}
      </button>
      <button
        type="button"
        class="foot-btn danger"
        :disabled="selected.size === 0"
        @click="removeSelected"
      >
        删除{{ selected.size > 0 ? ` ${selected.size}` : '' }}
      </button>
      <button type="button" class="foot-btn" @click="toggleManaging">完成</button>
    </footer>

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
        <button type="button" class="menu-item" @click.stop="pick('manage')">批量管理</button>
        <button type="button" class="menu-item danger" @click.stop="pick('remove')">删除</button>
      </div>
    </Teleport>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 264px;
  flex: 0 0 264px;
  height: 100%;
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

.search :deep(.el-input__wrapper) {
  height: 36px;
}

.list {
  flex: 1;
  /* 不加 min-height，内容会把侧栏撑高，超出的部分被外层 overflow:hidden 裁掉，滚不到 */
  min-height: 0;
  overflow-y: auto;
  /* 滚动条隐藏但保留滚动：滚轮、触控板双指和手机滑动都照常 */
  scrollbar-width: none;
  -ms-overflow-style: none;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.list::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}

.hint {
  margin: 8px 4px;
  font-size: 13px;
}

.sidebar-foot {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.foot-btn {
  border: none;
  background: none;
  padding: 4px 0;
  font-size: 12px;
  cursor: pointer;
  color: var(--sz-ink-muted);
}

.foot-btn:hover {
  color: var(--sz-brand-deep);
}

.foot-btn.danger {
  color: #c05a4e;
}

.foot-btn:disabled {
  opacity: 0.45;
  cursor: default;
}

.item.managing {
  flex-direction: row;
  align-items: flex-start;
}

.item-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.item.selected {
  background-color: color-mix(in srgb, var(--sz-brand) 12%, transparent);
}

.check {
  flex: 0 0 auto;
  margin-top: 4px;
  width: 14px;
  height: 14px;
  margin-right: 8px;
  border-radius: 4px;
  border: 1px solid var(--sz-line);
  background-color: var(--sz-surface);
}

.check.on {
  border-color: var(--sz-brand);
  background-color: var(--sz-brand);
  box-shadow: inset 0 0 0 2px var(--sz-surface);
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
  background-color: var(--sz-surface, #ffffff);
  box-shadow: var(--sz-shadow-soft);
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
