<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import AuthShell from '@/components/AuthShell.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  email: '',
  password: '',
  confirm: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]{4,32}$/,
      message: '4~32 位，只能包含字母、数字、下划线',
      trigger: 'blur',
    },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 128, message: '不能超过 128 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请设置密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/,
      message: '8~64 位，至少包含字母和数字',
      trigger: 'blur',
    },
  ],
  confirm: [
    { required: true, message: '请再输入一次密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

async function submit() {
  if (!formRef.value || loading.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.register({
      username: form.username.trim(),
      email: form.email.trim(),
      password: form.password,
    })
    ElMessage.success('注册成功，用这个账号登录吧')
    await router.replace({ name: 'login', query: { account: form.username.trim() } })
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
      <p class="head-eyebrow">第一次来</p>
      <h2 class="head-title">创建你的账号</h2>
      <p class="head-desc">注册后就能开始提问，回答和笔记都会自动留存。</p>
    </header>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      size="large"
      @submit.prevent="submit"
    >
      <el-form-item label="用户名" prop="username">
        <el-input
          v-model="form.username"
          placeholder="4~32 位，字母、数字或下划线"
          autocomplete="username"
          clearable
        />
      </el-form-item>

      <el-form-item label="邮箱" prop="email">
        <el-input
          v-model="form.email"
          placeholder="用来找回账号"
          autocomplete="email"
          clearable
        />
      </el-form-item>

      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="8~64 位，至少包含字母和数字"
          autocomplete="new-password"
          show-password
        />
      </el-form-item>

      <el-form-item label="确认密码" prop="confirm">
        <el-input
          v-model="form.confirm"
          type="password"
          placeholder="再输入一次"
          autocomplete="new-password"
          show-password
          @keyup.enter="submit"
        />
      </el-form-item>

      <el-button class="submit-btn" type="primary" :loading="loading" @click="submit">
        注册
      </el-button>
    </el-form>

    <p class="panel-switch">
      已经有账号了？
      <router-link :to="{ name: 'login' }">去登录</router-link>
    </p>
  </AuthShell>
</template>

<style scoped>
.panel-head {
  margin-bottom: 24px;
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
  margin: 20px 0 0;
  text-align: center;
  font-size: 13px;
}

.panel-switch a {
  font-weight: 600;
  text-decoration: none;
}
</style>
