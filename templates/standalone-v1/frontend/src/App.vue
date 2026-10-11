<script setup lang="ts">
import { computed } from 'vue'
import { useStartup } from './useStartup'

const { phase, failure, refresh } = useStartup()
const status = computed(
  () =>
    ({
      checking: '正在连接服务…',
      connected: '服务连接正常',
      failed: '暂时无法连接',
    })[phase.value],
)
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div class="app-shell">
    <header class="app-header">
      <span class="brand"><span class="brand-mark" aria-hidden="true">✳</span> atoms</span>
      <span class="instance-label">独立应用</span>
    </header>

    <main id="main-content" tabindex="-1">
      <section class="startup" aria-labelledby="app-title">
        <p class="eyebrow">APPLICATION</p>
        <h1 id="app-title">你的应用，从这里开始。</h1>
        <p class="intro">连接当前应用的服务，查看启动状态。</p>

        <div class="connection-panel" :data-state="phase">
          <p class="connection-status" role="status" aria-live="polite" aria-atomic="true">
            <span class="status-marker" aria-hidden="true">{{
              phase === 'connected' ? '✓' : phase === 'failed' ? '!' : '·'
            }}</span>
            {{ status }}
          </p>
          <p v-if="phase === 'connected'" class="detail">本次检查已确认应用服务与数据库可连接。</p>
          <p v-else-if="phase === 'checking'" class="detail">
            正在检查服务与数据库连接，最多等待 5 秒。
          </p>
          <p v-if="failure" class="error-message" role="alert">{{ failure.message }}</p>
          <button type="button" :aria-disabled="phase === 'checking'" @click="refresh">
            {{
              phase === 'checking' ? '连接中…' : phase === 'failed' ? '重试连接' : '重新检查连接'
            }}
            <span aria-hidden="true">↗</span>
          </button>
        </div>

        <aside class="implementation-note" aria-label="当前开发阶段">
          <h2>应用界面尚未接入</h2>
          <p>
            当前版本提供独立启动外壳。会话初始化、应用界面和数据保存将在后续步骤接入；连接检查通过也不代表应用已运行。
          </p>
        </aside>
      </section>
    </main>

    <footer class="app-footer">
      Atoms · 独立运行工程<span>连接检查不会创建访客或写入应用数据</span>
    </footer>
  </div>
</template>
