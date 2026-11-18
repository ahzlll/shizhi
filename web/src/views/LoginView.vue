<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import AuthShell from '@/components/AuthShell.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ account: '', password: '' })

const rules: FormRules = {
  account: [{ required: true, message: '请输入用户名或邮箱', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

onMounted(() => {
  const account = route.query.account
  if (typeof account === 'string') {
    form.account = account
  }
})

async function submit() {
  if (!formRef.value || loading.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.login(form.account.trim(), form.password)
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/chat'
    await router.replace(redirect)
  } catch {
    // 具体错误由请求拦截器统一提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthShell>
    <header class="panel-head">
      <p class="head-eyebrow">欢迎回来</p>
      <h2 class="head-title">登录你的知识库</h2>
      <p class="head-desc">继续上次的提问，或者开始一个新话题。</p>
    </header>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      size="large"
      @submit.prevent="submit"
    >
      <el-form-item label="用户名或邮箱" prop="account">
        <el-input
          v-model="form.account"
          placeholder="例如 zhangsan 或 you@example.com"
          autocomplete="username"
          clearable
          @keyup.enter="submit"
        />
      </el-form-item>

      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="请输入密码"
          autocomplete="current-password"
          show-password
          @keyup.enter="submit"
        />
      </el-form-item>

      <el-button class="submit-btn" type="primary" :loading="loading" @click="submit">
        登录
      </el-button>
    </el-form>

    <p class="panel-switch">
      还没有账号？
      <router-link :to="{ name: 'register' }">去注册</router-link>
    </p>
  </AuthShell>
</template>

<style scoped>
.panel-head {
  margin-bottom: 28px;
}

.head-eyebrow {
  margin: 0 0 6px;
  font-size: 13px;
  letter-spacing: 1px;
}

.head-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
}

.head-desc {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.7;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
  height: 44px;
  font-size: 15px;
  letter-spacing: 1px;
}

.panel-switch {
  margin: 22px 0 0;
  text-align: center;
  font-size: 13px;
}

.panel-switch a {
  font-weight: 600;
  text-decoration: none;
}
</style>
