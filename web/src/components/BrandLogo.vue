<template>
  <div class="brand">
    <span class="brand-name">拾知成图</span>
  </div>
</template>

<style scoped>
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 衬线 + 拉开字距，像书脊上的题字；渐变让四个字有墨色深浅的变化 */
.brand-name {
  font-family: 'Songti SC', 'Noto Serif SC', 'Source Han Serif SC', STSong, SimSun, serif;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.18em;
  /* 字距会在末尾多留一个字宽的空隙，补回来才和右侧元素对齐 */
  margin-right: -0.18em;
  background-image: linear-gradient(100deg, var(--sz-brand-deep) 0%, var(--sz-brand) 58%, var(--sz-brand-deep) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: var(--sz-brand-deep);
}

html[data-theme='dark'] .brand-name {
  background-image: linear-gradient(100deg, var(--sz-brand) 0%, #a8e6c6 58%, var(--sz-brand) 100%);
}
</style>
