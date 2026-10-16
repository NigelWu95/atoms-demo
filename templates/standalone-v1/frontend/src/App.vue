<script setup lang="ts">
import { computed } from 'vue'
import { useStartup } from './useStartup'
import RuntimeFrame from './RuntimeFrame.vue'

const { phase, failure, app, requestId, refresh } = useStartup()
const busy = computed(() => phase.value === 'checking' || phase.value === 'connecting')
const status = computed(
  () =>
    ({
      checking: '正在连接服务…',
      connecting: '正在建立会话并读取应用…',
      connected: '应用信息已就绪',
      failed: '暂时无法连接',
    })[phase.value],
)
function reload() {
  if (busy.value) return
  if (
    app.value &&
    !window.confirm(
      '重新加载会清除未保存的输入及待确认操作的重试信息，但不会撤销服务器可能已经完成的保存。是否继续？',
    )
  )
    return
  void refresh()
}
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div class="app-shell">
    <header class="app-header">
      <span class="brand"><span class="brand-mark" aria-hidden="true">✳</span> atoms</span>
      <span class="instance-label">独立应用</span>
    </header>

    <main id="main-content" :class="{ 'has-app': app }" tabindex="-1">
      <section class="startup" :class="{ 'has-app': app }" aria-labelledby="app-title">
        <p v-if="!app" class="eyebrow">APPLICATION</p>
        <h1 id="app-title">{{ app?.title ?? '正在打开独立应用' }}</h1>
        <p v-if="!app" class="intro">连接当前应用的服务，建立独立访客会话。</p>

        <div class="connection-panel" :data-state="phase">
          <p
            class="connection-status"
            role="status"
            aria-label="服务连接状态"
            aria-live="polite"
            aria-atomic="true"
          >
            <span class="status-marker" aria-hidden="true">{{
              phase === 'connected' ? '✓' : phase === 'failed' ? '!' : '·'
            }}</span>
            {{ status }}
          </p>
          <p v-if="busy" class="detail">正在检查服务、建立会话并读取应用，最多等待 15 秒。</p>
          <p v-if="failure" class="error-message" role="alert">{{ failure.message }}</p>
          <p v-if="requestId" class="detail">请求编号：{{ requestId }}</p>
          <button type="button" :aria-disabled="busy" @click="reload">
            {{ busy ? '连接中…' : phase === 'failed' ? '重试连接' : '重新加载应用' }}
            <span aria-hidden="true">↗</span>
          </button>
        </div>

        <RuntimeFrame v-if="app" :title="app.title" :loaded-runtime-id="app.loadedRuntimeId" />
        <details v-if="app" class="app-information">
          <summary>应用说明</summary>
          <p>{{ app.summary }}</p>
          <p class="detail">访客会话已连接，已读取当前应用信息。</p>
        </details>
      </section>
    </main>

    <footer class="app-footer">
      Atoms · 独立运行工程<span>独立访客会话 · 数据不与平台共用</span>
    </footer>
  </div>
</template>
