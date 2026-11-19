<script setup lang="ts">
// embedded：作为弹窗内容渲染时不带页面头与卡片外框
defineProps<{ embedded?: boolean }>()

import BrandLogo from '@/components/BrandLogo.vue'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { submitFeedback } from '@/api/account'

const router = useRouter()
const route = useRoute()

const sending = ref(false)
const form = reactive({ content: '', contact: '' })

async function submit() {
  if (!form.content.trim()) {
    ElMessage.warning('先写点什么吧')
    return
  }
  sending.value = true
  try {
    await submitFeedback({
      content: form.content.trim(),
      contact: form.contact.trim() || undefined,
      page: route.query.from ? String(route.query.from) : '/chat',
    })
    form.content = ''
    form.contact = ''
    ElMessage.success('收到了，谢谢')
  } finally {
    sending.value = false
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
        <h1 v-if="!embedded" class="card-title">意见反馈</h1>
        <p class="card-desc">哪里用着别扭、缺什么功能，都可以写在这里。</p>

        <el-input
          v-model="form.content"
          type="textarea"
          :autosize="{ minRows: 5, maxRows: 10 }"
          maxlength="1000"
          show-word-limit
          placeholder="说说你的想法"
        />

        <div class="contact">
          <el-input v-model="form.contact" maxlength="128" placeholder="想收到回复的话，留个联系方式（选填）" />
        </div>

        <el-button type="primary" class="submit" :loading="sending" @click="submit">提交</el-button>
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
  margin: 0 0 8px;
  font-size: 20px;
}

.card-desc {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--sz-ink-muted);
}

.contact {
  margin-top: 12px;
}

.submit {
  width: 100%;
  height: 44px;
  margin-top: 18px;
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
