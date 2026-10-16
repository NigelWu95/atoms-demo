<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiFailure, errorMessage } from '../api/client'
import { emailMethods, sendEmailCode, type EmailChallenge } from '../api/emailAuth'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const enabled = ref<boolean | null>(null),
  loading = ref(false),
  busy = ref(false)
const email = ref(''),
  code = ref(''),
  error = ref(''),
  notice = ref('')
const challenge = ref<EmailChallenge | null>(null),
  now = ref(Date.now()),
  resendAt = ref(0)
const codeInput = ref<HTMLInputElement | null>(null)
const wait = computed(() => Math.max(0, Math.ceil((resendAt.value - now.value) / 1000)))
const expired = computed(
  () => challenge.value !== null && Date.parse(challenge.value.expiresAt) <= now.value,
)
const emailValid = computed(
  () =>
    /^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/.test(email.value.trim()) &&
    email.value.trim().length <= 254,
)
const registered = computed(() => session.ready && session.identity?.isGuest === false)
const controller = new AbortController()
let mounted = true
let timer: ReturnType<typeof setInterval>
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const value = await emailMethods()
    if (mounted) enabled.value = value.emailCodeEnabled
  } catch (failure) {
    if (mounted) error.value = errorMessage(failure)
  } finally {
    if (mounted) loading.value = false
  }
}
async function send() {
  if (busy.value || wait.value || !session.ready || !enabled.value || !emailValid.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const sent = await sendEmailCode(email.value.trim(), controller.signal)
    if (!mounted) return
    challenge.value = sent
    code.value = ''
    now.value = Date.now()
    resendAt.value = now.value + sent.resendAfterSeconds * 1000
    notice.value = '验证码已发送，请查看收件箱或垃圾邮件。10 分钟内有效。'
    busy.value = false
    await nextTick()
    codeInput.value?.focus()
  } catch (failure) {
    if (!mounted) return
    error.value = errorMessage(failure)
    if (failure instanceof ApiFailure) {
      // Unknown sends are never retried automatically; retain a prior successful challenge.
      const delay =
        failure.retryAfter ??
        (['NETWORK_ERROR', 'EMAIL_DELIVERY_UNCONFIRMED'].includes(failure.code) ? 60 : 0)
      now.value = Date.now()
      resendAt.value = now.value + delay * 1000
    }
  } finally {
    if (mounted) busy.value = false
  }
}
async function verify() {
  if (
    busy.value ||
    !session.ready ||
    !challenge.value ||
    expired.value ||
    !/^\d{6}$/.test(code.value)
  )
    return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await session.login(challenge.value.challengeId, code.value)
    if (mounted) notice.value = '登录成功，项目已关联当前账号。'
  } catch (failure) {
    if (mounted) error.value = errorMessage(failure)
  } finally {
    if (mounted) {
      busy.value = false
      code.value = ''
    }
  }
}
async function logout() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await session.logout()
    if (mounted) notice.value = '已退出账号，现在是新的访客会话。'
  } catch (failure) {
    if (mounted) error.value = errorMessage(failure)
  } finally {
    if (mounted) busy.value = false
  }
}
function changeEmail() {
  challenge.value = null
  code.value = ''
  notice.value = ''
  error.value = ''
}
watch(
  () => [session.identity?.userId, session.identity?.isGuest],
  () => {
    email.value = ''
    code.value = ''
    challenge.value = null
    notice.value = ''
  },
)
onMounted(() => {
  void load()
  timer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})
onBeforeUnmount(() => {
  mounted = false
  controller.abort()
  clearInterval(timer)
})
</script>

<template>
  <section class="simple-page account-page">
    <p class="eyebrow">YOUR ACCOUNT</p>
    <h1 tabindex="-1">{{ registered ? '账号与工作空间' : '让灵感，有处可存' }}</h1>
    <p class="account-intro">用邮箱验证码登录，在不同浏览器继续你的创作。无需设置密码。</p>
    <div class="account-layout">
      <div class="account-card">
        <template v-if="registered">
          <span class="account-badge">邮箱账号 · 已登录</span>
          <h2>{{ session.identity?.displayName }}</h2>
          <p>
            项目、历史对话和应用版本归属当前账号。退出不会删除已保存的内容，也不会停止正在进行的生成。
          </p>
          <RouterLink to="/projects" class="button primary">回到我的项目</RouterLink>
          <button class="button secondary" type="button" :disabled="busy" @click="logout">
            {{ busy ? '正在退出…' : '退出账号' }}
          </button>
        </template>
        <template v-else-if="enabled === false">
          <h2>邮箱登录暂未开放</h2>
          <p>
            管理员需要先配置发信邮箱。公网访问还需要 HTTPS；本机开发可使用 localhost 或 127.0.0.1。
          </p>
          <p>你仍可以用访客身份创作，当前浏览器的项目会保存在服务端。</p>
          <RouterLink to="/" class="button primary">继续访客创作</RouterLink>
        </template>
        <form v-else-if="enabled" @submit.prevent="verify">
          <h2>邮箱注册 / 登录</h2>
          <p id="email-help">首次验证会创建账号，已有邮箱会直接登录。</p>
          <label for="account-email">邮箱地址</label>
          <input
            id="account-email"
            v-model="email"
            type="email"
            autocomplete="email"
            inputmode="email"
            maxlength="254"
            required
            :readonly="!!challenge"
            :disabled="busy || !session.ready"
            aria-describedby="email-help account-warning"
            placeholder="you@example.com"
          />
          <div class="account-send-row">
            <button
              class="button secondary"
              type="button"
              data-test="send-code"
              :disabled="busy || !session.ready || !!wait || !emailValid"
              @click="send"
            >
              {{
                busy && !challenge
                  ? '正在发送…'
                  : wait
                    ? `${wait} 秒后可重发`
                    : challenge
                      ? '重新发送验证码'
                      : '发送验证码'
              }}
            </button>
            <button
              v-if="challenge"
              type="button"
              class="button secondary"
              :disabled="busy || !session.ready"
              @click="changeEmail"
            >
              更换邮箱
            </button>
          </div>
          <template v-if="challenge">
            <label for="account-code">6 位验证码</label>
            <input
              id="account-code"
              ref="codeInput"
              v-model="code"
              type="text"
              inputmode="numeric"
              autocomplete="one-time-code"
              pattern="[0-9]{6}"
              maxlength="6"
              required
              :disabled="busy || !session.ready || expired"
              aria-describedby="code-help"
            />
            <p id="code-help">
              {{
                expired
                  ? '验证码已过期，请重新发送。'
                  : '请使用此浏览器请求的验证码；连续输错 5 次需重新获取。'
              }}
            </p>
            <button
              class="button primary account-submit"
              type="submit"
              :disabled="busy || !session.ready || expired || !/^\d{6}$/.test(code)"
            >
              {{ busy ? '正在验证…' : '验证并登录' }}
            </button>
          </template>
        </form>
        <div v-else>
          <h2>邮箱注册 / 登录</h2>
          <p v-if="loading" role="status">正在读取登录方式…</p>
          <button v-else type="button" class="button secondary" @click="load">
            重试读取登录方式
          </button>
        </div>
        <p v-if="error" class="account-error" role="alert">{{ error }}</p>
        <p v-if="notice" class="account-notice" role="status">{{ notice }}</p>
        <p v-if="!session.ready" class="muted">请等待会话连接；连接失败时可在侧栏重新连接。</p>
      </div>
      <aside class="account-details" aria-label="账号说明">
        <span class="eyebrow">KEEP CREATING</span>
        <h2>从访客，到你的工作空间</h2>
        <p>新邮箱注册后，这个浏览器里已有的项目、对话、版本和发布链接都保留。</p>
        <p id="account-warning" class="account-warning">
          登录已有邮箱会切换到该账号，不会合并当前访客的项目。重要内容请先下载保存。
        </p>
        <p>
          验证码不会保存到浏览器存储。未注册的访客身份依赖 Cookie，清除 Cookie 后无法通过邮箱找回。
        </p>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.account-page {
  max-width: 1100px;
  margin: auto;
}
.account-intro,
.account-card p,
.account-details p {
  color: var(--muted);
  line-height: 1.8;
}
.account-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 40px;
  margin-top: 32px;
  align-items: start;
}
.account-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 20px;
  padding: 32px;
  min-width: 0;
}
.account-card h2 {
  margin: 0 0 16px;
  font-size: 22px;
}
.account-card label {
  display: block;
  font-weight: 600;
  margin: 20px 0 8px;
}
.account-card input {
  width: 100%;
  min-height: 48px;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--bg);
  color: var(--text);
  font: inherit;
}
.account-card input[readonly] {
  color: var(--muted);
}
.account-send-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.account-submit {
  width: 100%;
}
.account-details {
  padding: 24px 0;
}
.account-details p + p,
.account-notice,
.account-error {
  margin-top: 12px;
}
.account-details h2 {
  font-size: 24px;
  line-height: 1.5;
  margin: 16px 0;
}
.account-warning {
  border-left: 2px solid var(--accent);
  padding-left: 16px;
}
.account-card .account-error {
  color: #ffb4a5;
  overflow-wrap: anywhere;
}
.account-card .account-notice,
.account-badge {
  color: var(--accent);
}
.account-badge {
  display: inline-block;
  margin-bottom: 20px;
  font-size: 14px;
}
.account-card > .button {
  margin: 12px 12px 0 0;
}
@media (max-width: 800px) {
  .account-layout {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .account-card {
    padding: 24px;
  }
  .account-details {
    padding: 12px 0;
  }
}
</style>
