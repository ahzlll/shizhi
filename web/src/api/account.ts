import { http } from '@/api/http'

export interface ModelConfigView {
  enabled: boolean
  configured: boolean
  baseUrl: string | null
  model: string | null
  apiKeyMask: string | null
}

export interface SaveModelConfigPayload {
  baseUrl: string
  model: string
  apiKey: string
}

export interface ProfileView {
  id: number
  username: string
  email: string
  nickname: string | null
  avatarVersion: string | null
  createdAt: string
}

export interface FeedbackPayload {
  content: string
  contact?: string
  page?: string
}

export function getModelConfig() {
  return http.get<ModelConfigView>('/account/model')
}

export function saveModelConfig(payload: SaveModelConfigPayload) {
  return http.put<ModelConfigView>('/account/model', payload)
}

export function deleteModelConfig() {
  return http.delete<void>('/account/model')
}

export function getProfile() {
  return http.get<ProfileView>('/account/profile')
}

export function updateProfile(nickname: string) {
  return http.put<ProfileView>('/account/profile', { nickname })
}

export function deleteAvatar() {
  return http.delete<ProfileView>('/account/avatar')
}

export function submitFeedback(payload: FeedbackPayload) {
  return http.post<void>('/feedback', payload)
}

/**
 * 上传走原生 fetch：axios 实例统一按 JSON 序列化，multipart 需要浏览器自己带 boundary。
 */
export async function uploadAvatar(file: File, token: string): Promise<ProfileView> {
  const body = new FormData()
  body.append('file', file)
  const response = await fetch('/api/account/avatar', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body,
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok || payload?.code !== 0) {
    throw new Error(payload?.message ?? '上传失败')
  }
  return payload.data as ProfileView
}

/** 头像地址带版本号：换了图 URL 就变，浏览器不会拿旧缓存。 */
export function avatarUrl(profile: ProfileView | null): string | null {
  if (!profile?.avatarVersion) return null
  const version = Date.parse(profile.avatarVersion) || 0
  return `/api/account/avatar/${profile.id}?v=${version}`
}
