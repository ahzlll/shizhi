<script setup lang="ts">
// embedded：作为弹窗内容渲染时不带页面头与卡片外框
defineProps<{ embedded?: boolean }>()

import BrandLogo from '@/components/BrandLogo.vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { deleteAvatar, updateProfile, uploadAvatar } from '@/api/account'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/time'

const auth = useAuthStore()
const router = useRouter()

const nickname = ref('')
const saving = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()

const profile = computed(() => auth.profile)

onMounted(async () => {
  await auth.loadProfile()
  nickname.value = profile.value?.nickname ?? ''
})

function chooseFile() {
  fileInput.value?.click()
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return

  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('图片不能超过 2MB')
    return
  }

  uploading.value = true
  try {
    const updated = await uploadAvatar(file, auth.accessToken)
    auth.setProfile(updated)
    ElMessage.success('头像已更新')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
}

async function removeAvatar() {
  const updated = await deleteAvatar()
  auth.setProfile(updated)
  ElMessage.success('已恢复默认头像')
}

async function saveNickname() {
  saving.value = true
  try {
    const updated = await updateProfile(nickname.value.trim())
    auth.setProfile(updated)
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
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
        <h1 v-if="!embedded" class="card-title">个人主页</h1>

        <section class="avatar-row">
          <img v-if="auth.avatar" :src="auth.avatar" class="avatar" alt="头像" />
          <span v-else class="avatar avatar-fallback">{{ auth.initial }}</span>

          <div class="avatar-actions">
            <el-button :loading="uploading" @click="chooseFile">上传头像</el-button>
            <el-button v-if="auth.avatar" link type="danger" @click="removeAvatar">删除</el-button>
            <p class="hint">支持 png / jpeg / webp，不超过 2MB</p>
            <input
              ref="fileInput"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              hidden
              @change="onFileChange"
            />
          </div>
        </section>

        <section class="field">
          <label class="label">昵称</label>
          <div class="field-row">
            <el-input v-model="nickname" maxlength="32" placeholder="怎么称呼你" />
            <el-button type="primary" :loading="saving" @click="saveNickname">保存</el-button>
          </div>
        </section>

        <section class="meta">
          <div class="meta-row">
            <span class="meta-label">用户名</span>
            <span class="meta-value">{{ profile?.username }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label">邮箱</span>
            <span class="meta-value">{{ profile?.email }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label">注册时间</span>
            <span class="meta-value">{{ profile ? formatTime(profile.createdAt) : '' }}</span>
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
  margin: 0 0 22px;
  font-size: 20px;
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 26px;
}

.avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  object-fit: cover;
}

.avatar-fallback {
  display: grid;
  place-items: center;
  background-color: var(--sz-brand-soft);
  color: var(--sz-brand-deep);
  font-size: 26px;
  font-weight: 600;
}

.avatar-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.hint {
  flex-basis: 100%;
  margin: 0;
  font-size: 12px;
  color: var(--sz-ink-muted);
}

.field {
  margin-bottom: 26px;
}

.label {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--sz-ink-muted);
}

.field-row {
  display: flex;
  gap: 10px;
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 20px;
  border-top: 1px solid var(--sz-line);
}

.meta-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}

.meta-label {
  color: var(--sz-ink-muted);
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
