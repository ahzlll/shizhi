import { ref } from 'vue'
import { defineStore } from 'pinia'

export type ThemeMode = 'light' | 'dark'
export type ThemeAccent = 'green' | 'pink' | 'blue' | 'neutral'
export type Density = 'cozy' | 'compact'

const MODE_KEY = 'sz_theme_mode'
const ACCENT_KEY = 'sz_theme_accent'
const DENSITY_KEY = 'sz_theme_density'

export const ACCENTS: { value: ThemeAccent; label: string; color: string }[] = [
  { value: 'green', label: '淡绿', color: '#52b788' },
  { value: 'pink', label: '樱粉', color: '#e08fb3' },
  { value: 'blue', label: '雾蓝', color: '#6aa9e0' },
  { value: 'neutral', label: '暖灰', color: '#9aa8a1' },
]

/**
 * 外观偏好存在浏览器里，不占后端一张表：它只影响这台设备的观感，
 * 换设备重新选一次比多一个同步接口更简单。
 */
export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readMode())
  const accent = ref<ThemeAccent>(readAccent())
  const density = ref<Density>(readDensity())

  function apply() {
    const root = document.documentElement
    root.dataset.theme = mode.value
    root.dataset.accent = accent.value
    root.dataset.density = density.value
    // Element Plus 的深色样式挂在 html.dark 上，两个地方都要标
    root.classList.toggle('dark', mode.value === 'dark')
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    localStorage.setItem(MODE_KEY, next)
    apply()
  }

  function setAccent(next: ThemeAccent) {
    accent.value = next
    localStorage.setItem(ACCENT_KEY, next)
    apply()
  }

  function setDensity(next: Density) {
    density.value = next
    localStorage.setItem(DENSITY_KEY, next)
    apply()
  }

  function init() {
    apply()
  }

  return { mode, accent, density, setMode, setAccent, setDensity, init }
})

function readMode(): ThemeMode {
  const saved = localStorage.getItem(MODE_KEY)
  return saved === 'dark' ? 'dark' : 'light'
}

function readDensity(): Density {
  return localStorage.getItem(DENSITY_KEY) === 'compact' ? 'compact' : 'cozy'
}

function readAccent(): ThemeAccent {
  const saved = localStorage.getItem(ACCENT_KEY)
  return saved === 'pink' || saved === 'blue' || saved === 'neutral' || saved === 'green'
    ? saved
    : 'green'
}
