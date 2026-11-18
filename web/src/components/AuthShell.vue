<template>
  <div class="auth-shell">
    <section class="auth-brand">
      <div class="brand-mark">拾</div>
      <h1 class="brand-title">拾知成图</h1>
      <p class="brand-subtitle">把零散的提问，长成一张属于你自己的知识地图。</p>
      <ul class="brand-points">
        <li>边问边记，回答会自动整理成笔记</li>
        <li>知识点之间自动连线，越用越清楚</li>
        <li>按记忆曲线提醒复习，不用自己排计划</li>
      </ul>
    </section>

    <main class="auth-panel">
      <div class="panel-card">
        <slot />
      </div>
      <p class="panel-foot">拾知成图 · 个人知识库</p>
    </main>
  </div>
</template>

<style scoped>
.auth-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(380px, 520px);
}

.auth-brand {
  padding: 72px 64px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 18px;
}

.brand-mark {
  width: 52px;
  height: 52px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  font-size: 26px;
  font-weight: 600;
}

.brand-title {
  margin: 0;
  font-size: 34px;
  letter-spacing: 2px;
}

.brand-subtitle {
  margin: 0;
  max-width: 380px;
  font-size: 15px;
  line-height: 1.9;
}

.brand-points {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 14px;
}

.brand-points li {
  position: relative;
  padding-left: 20px;
}

.brand-points li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.auth-panel {
  padding: 48px 32px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.panel-card {
  width: 100%;
  max-width: 400px;
  padding: 40px 36px;
  border-radius: 20px;
}

.panel-foot {
  margin: 24px 0 0;
  font-size: 12px;
}

@media (max-width: 900px) {
  .auth-shell {
    grid-template-columns: 1fr;
  }

  .auth-brand {
    display: none;
  }

  .auth-panel {
    min-height: 100vh;
    padding: 24px 16px;
  }

  .panel-card {
    padding: 28px 22px;
  }
}
</style>
