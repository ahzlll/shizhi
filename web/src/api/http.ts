import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'

interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

const raw = axios.create({
  baseURL: '/api',
  timeout: 20000,
})

raw.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

let refreshing: Promise<boolean> | null = null

raw.interceptors.response.use(
  (response) => {
    const body = response.data as ApiEnvelope<unknown>
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        ElMessage.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      // 拦截器把信封拆掉，调用方直接拿到 data；类型在下面的 http.get / post 里补回来
      return body.data as unknown as typeof response
    }
    return response
  },
  async (error: AxiosError<ApiEnvelope<unknown>>) => {
    const config = error.config as RetriableConfig | undefined
    const status = error.response?.status
    const auth = useAuthStore()
    const isRefreshCall = Boolean(config?.url?.includes('/auth/refresh'))

    // 401 时先尝试续期一次；刷新请求自己不再递归
    if (status === 401 && config && !config._retried && !isRefreshCall && auth.refreshToken) {
      config._retried = true
      if (!refreshing) {
        refreshing = auth.refresh().finally(() => {
          refreshing = null
        })
      }
      const renewed = await refreshing
      if (renewed) {
        return raw(config)
      }
    }

    const message =
      error.response?.data?.message || (status ? `请求失败（${status}）` : '网络连接失败，请检查后端是否启动')
    ElMessage.error(message)

    if (status === 401 && !isRefreshCall) {
      auth.clear()
      if (!window.location.pathname.startsWith('/login')) {
        const back = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.assign(`/login?redirect=${back}`)
      }
    }
    return Promise.reject(error)
  },
)

export const http = {
  get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    return raw.get(url, { params }) as unknown as Promise<T>
  },
  post<T>(url: string, data?: unknown): Promise<T> {
    return raw.post(url, data) as unknown as Promise<T>
  },
  patch<T>(url: string, data?: unknown): Promise<T> {
    return raw.patch(url, data) as unknown as Promise<T>
  },
  put<T>(url: string, data?: unknown): Promise<T> {
    return raw.put(url, data) as unknown as Promise<T>
  },
  delete<T>(url: string): Promise<T> {
    return raw.delete(url) as unknown as Promise<T>
  },
}
