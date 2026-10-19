<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

const state = ref<'loading' | 'ready' | 'error'>('loading')
const message = ref('')
let request: AbortController | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

async function checkConnection() {
  if (request || disposed) return
  const controller = new AbortController()
  request = controller
  state.value = 'loading'
  let timedOut = false
  timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, 10_000)
  try {
    const response = await fetch('./api/health', {
      method: 'GET',
      credentials: 'omit',
      redirect: 'error',
      cache: 'no-store',
      signal: controller.signal,
    })
    if (!response.ok) throw new Error('Health request failed')
    const body: unknown = await response.json()
    if (
      typeof body !== 'object' ||
      body === null ||
      !('status' in body) ||
      !('stage' in body) ||
      body.status !== 'UP' ||
      body.stage !== 'INITIALIZED'
    )
      throw new Error('Unexpected health response')
    if (!disposed) state.value = 'ready'
  } catch {
    if (!disposed) {
      state.value = 'error'
      message.value = timedOut
        ? '请求超时，请确认应用后端已启动后重试。'
        : '暂时无法连接应用后端，请确认后端已启动后重试。'
    }
  } finally {
    clearTimeout(timer)
    timer = undefined
    request = undefined
  }
}

// https://vuejs.org/guide/essentials/lifecycle.html
onMounted(() => {
  void checkConnection()
})
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
  request?.abort()
})
</script>

<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <main id="main" tabindex="-1">
    <header>
      <p class="eyebrow">应用工程 · FULL PROJECT</p>
      <h1>从这里，开始构建。</h1>
      <p class="intro">
        原生 Vue 页面和 Java API 已有各自的工程入口。这里是初始化状态页，尚未生成业务功能。
      </p>
    </header>

    <section class="connection" aria-labelledby="connection-title">
      <div>
        <h2 id="connection-title">应用连接</h2>
        <p role="status" aria-live="polite">
          <template v-if="state === 'loading'">正在检查应用后端…</template>
          <template v-else-if="state === 'ready'">前后端已连通</template>
          <template v-else>连接尚未就绪</template>
        </p>
        <p v-if="state === 'error'" class="error" role="alert">{{ message }}</p>
      </div>
      <button type="button" :disabled="state === 'loading'" @click="checkConnection">
        {{ state === 'loading' ? '正在检查…' : state === 'error' ? '重试连接' : '重新检查' }}
      </button>
    </section>
    <p class="note">连接检查仅验证本应用的 HTTP 接口，不代表数据库、业务功能或部署已验收。</p>

    <section class="next" aria-labelledby="next-title">
      <h2 id="next-title">下一步，填入你的业务</h2>
      <ol>
        <li>在 Vue 组件中实现页面和交互。</li>
        <li>在 Java API 与 SQL 迁移中实现业务和数据保存。</li>
        <li>运行工程自身的测试，再构建和启动应用。</li>
      </ol>
      <p class="note">源码与运行配置分开保存。数据库连接和密码只在后端配置，不放进页面代码。</p>
    </section>
  </main>
</template>

<style src="./app.css"></style>
