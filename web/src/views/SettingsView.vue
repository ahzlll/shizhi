<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import {
  deleteModelConfig,
  getModelConfig,
  saveModelConfig,
  type ModelConfigView,
} from '@/api/account'

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const config = ref<ModelConfigView | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  baseUrl: '',
  model: '',
  apiKey: '',
})

const rules: FormRules = {
  baseUrl: [
    { required: true, message: '请输入接口地址', trigger: 'blur' },
    { pattern: /^https?:\/\/.+/, message: '需要以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
  model: [{ required: true, message: '请输入模型名', trigger: 'blur' }],
  apiKey: [{ required: true, message: '请输入 API key', trigger: 'blur' }],
}

onMounted(async () => {
  try {
    config.value = await getModelConfig()
    if (config.value.configured) {
      form.baseUrl = config.value.baseUrl ?? ''
      form.model = config.value.model ?? ''
    }
  } finally {
    loading.value = false
  }
})

async function save() {
  if (!formRef.value || saving.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    config.value = await saveModelConfig({
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiKey: form.apiKey.trim(),
    })
    form.apiKey = ''
    ElMessage.success('已保存，之后这个账号的对话都会用它')
  } finally {
    saving.value = false
  }
}

async function remove() {
  const confirmed = await ElMessageBox.confirm('删除后回到服务端默认模型，确定吗？', '删除配置', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return

  await deleteModelConfig()
  config.value = await getModelConfig()
  form.apiKey = ''
  ElMessage.success('已删除')
}
</script>

<template>
  <div class="settings-page">
    <header class="top-bar">
      <div class="brand">
        <span class="brand-mark">拾</span>
        <span class="brand-name">拾知成图</span>
      </div>
      <el-button link @click="router.push('/chat')">返回对话</el-button>
    </header>

    <main class="content">
      <div class="card">
        <h1 class="card-title">模型设置</h1>
        <p class="card-desc">
          填自己的接口地址和 key，之后的对话就用你的模型。不填则用服务端默认模型。
        </p>

        <el-alert
          v-if="config && !config.enabled"
          type="warning"
          :closable="false"
          show-icon
          title="服务端没有配置加密主密钥，暂时不能保存"
          description="需要服务端设置环境变量 SHIZHI_SECRET_KEY 后才能启用这个功能。"
        />

        <template v-else>
          <div v-if="config?.configured" class="current">
            <div>
              <span class="current-label">当前使用</span>
              <span class="current-value">{{ config.model }}</span>
            </div>
            <div class="current-sub">{{ config.baseUrl }} · key {{ config.apiKeyMask }}</div>
            <el-button link type="danger" size="small" @click="remove">删除这份配置</el-button>
          </div>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large">
            <el-form-item label="接口地址" prop="baseUrl">
              <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
            </el-form-item>
            <el-form-item label="模型名" prop="model">
              <el-input v-model="form.model" placeholder="deepseek-chat" />
            </el-form-item>
            <el-form-item label="API key" prop="apiKey">
              <el-input
                v-model="form.apiKey"
                type="password"
                show-password
                :placeholder="config?.configured ? '重新填写才会覆盖已保存的 key' : 'sk-...'"
              />
            </el-form-item>
            <el-button type="primary" class="save" :loading="saving" @click="save">
              保存并启用
            </el-button>
          </el-form>

          <p class="tip">
            只支持 OpenAI 兼容接口（/chat/completions）。key 加密存库，不回显明文。
          </p>
        </template>
      </div>
    </main>
  </div>
</template>

<style scoped>
.settings-page {
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
  padding: 28px 28px 24px;
  border-radius: 18px;
}

.card-title {
  margin: 0 0 8px;
  font-size: 20px;
}

.card-desc {
  margin: 0 0 22px;
  font-size: 13px;
  line-height: 1.8;
}

.current {
  margin-bottom: 20px;
  padding: 12px 14px;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.current-label {
  font-size: 12px;
  margin-right: 6px;
}

.current-value {
  font-size: 14px;
  font-weight: 600;
}

.current-sub {
  font-size: 12px;
}

.save {
  width: 100%;
  height: 44px;
  margin-top: 4px;
}

.tip {
  margin: 18px 0 0;
  font-size: 12px;
  line-height: 1.8;
}
</style>
