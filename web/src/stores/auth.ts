import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { http } from '@/api/http'

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresIn?: number
}

export interface UserProfile {
  id: number
  username: string
  email: string
}

export interface RegisterPayload {
  username: string
  email: string
  password: string
}

const ACCESS_KEY = 'sz_access_token'
const REFRESH_KEY = 'sz_refresh_token'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem(ACCESS_KEY) ?? '')
  const refreshToken = ref(localStorage.getItem(REFRESH_KEY) ?? '')
  const user = ref<UserProfile | null>(null)

  const isLoggedIn = computed(() => Boolean(accessToken.value))
  const displayName = computed(() => user.value?.username ?? '同学')

  function setTokens(tokens: TokenResponse) {
    accessToken.value = tokens.accessToken
    refreshToken.value = tokens.refreshToken
    localStorage.setItem(ACCESS_KEY, tokens.accessToken)
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
  }

  function clear() {
    accessToken.value = ''
    refreshToken.value = ''
    user.value = null
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  }

  async function login(account: string, password: string) {
    const tokens = await http.post<TokenResponse>('/auth/login', { account, password })
    setTokens(tokens)
    await loadMe()
  }

  async function register(payload: RegisterPayload) {
    await http.post('/auth/register', payload)
  }

  async function loadMe() {
    user.value = await http.get<UserProfile>('/auth/me')
  }

  async function refresh() {
    if (!refreshToken.value) return false
    try {
      const tokens = await http.post<TokenResponse>('/auth/refresh', {
        refreshToken: refreshToken.value,
      })
      setTokens(tokens)
      return true
    } catch {
      return false
    }
  }

  return {
    accessToken,
    refreshToken,
    user,
    isLoggedIn,
    displayName,
    login,
    register,
    loadMe,
    refresh,
    clear,
  }
})
