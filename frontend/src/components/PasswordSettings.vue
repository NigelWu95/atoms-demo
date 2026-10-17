<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiFailure, errorMessage } from '../api/client'
import { emailMethods, type EmailChallenge } from '../api/emailAuth'
import {
  getPasswordStatus,
  passwordValid,
  sendPasswordCode,
  type PasswordStatus,
} from '../api/passwords'
import { useSessionStore } from '../stores/session'

defineProps<{ email: string }>()
const session = useSessionStore()
const status = ref<PasswordStatus | null>(null),
  enabled = ref(false),
  loading = ref(false),
  busy = ref(false)
const challenge = ref<EmailChallenge | null>(null),
  code = ref(''),
  currentPassword = ref(''),
  newPassword = ref(''),
  confirmPassword = ref('')
const error = ref(''),
  notice = ref(''),
  show = ref(false),
  now = ref(Date.now()),
  resendAt = ref(0)
const wait = computed(() => Math.max(0, Math.ceil((resendAt.value - now.value) / 1000)))
const expired = computed(
  () => !!challenge.value && Date.parse(challenge.value.expiresAt) <= now.value,
)
const valid = computed(
  () =>
    passwordValid(newPassword.value) &&
    newPassword.value === confirmPassword.value &&
    (status.value?.configured
      ? !!currentPassword.value
      : !!challenge.value && !expired.value && /^\d{6}$/.test(code.value)),
)
const controller = new AbortController()
let mounted = true,
  timer: ReturnType<typeof setInterval>
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [value, methods] = await Promise.all([
      getPasswordStatus(controller.signal),
      emailMethods(),
    ])
    if (mounted) {
      status.value = value
      enabled.value = methods.passwordEnabled
    }
  } catch (failure) {
    if (mounted) error.value = errorMessage(failure)
  } finally {
    if (mounted) loading.value = false
  }
}
async function send() {
  if (busy.value || !session.ready || wait.value || !enabled.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const value = await sendPasswordCode(controller.signal)
    if (!mounted) return
    challenge.value = value
    code.value = ''
    now.value = Date.now()
    resendAt.value = now.value + value.resendAfterSeconds * 1000
    notice.value = '设置密码验证码已发送，10 分钟内有效。'
  } catch (failure) {
    if (!mounted) return
    error.value = errorMessage(failure)
    if (failure instanceof ApiFailure) {
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
async function save() {
  if (busy.value || !session.ready || !enabled.value || !valid.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await session.changePassword(
      status.value?.configured
        ? { mode: 'change', currentPassword: currentPassword.value, newPassword: newPassword.value }
        : {
            mode: 'setup',
            challengeId: challenge.value!.challengeId,
            code: code.value,
            newPassword: newPassword.value,
          },
    )
    if (!mounted || !result) return
    status.value = result
    challenge.value = null
    notice.value = '密码已更新。当前账号保持登录，其他浏览器会话需要重新登录。'
  } catch (failure) {
    if (mounted) error.value = errorMessage(failure)
  } finally {
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    code.value = ''
    if (mounted) busy.value = false
  }
}
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
  <section class="account-card password-settings" aria-labelledby="password-title">
    <h2 id="password-title">{{ status?.configured ? '修改密码' : '设置密码' }}</h2>
    <p v-if="loading" role="status">正在读取密码设置…</p>
    <template v-else-if="status">
      <p class="muted">
        {{
          status.configured
            ? '验证原密码后修改。忘记密码仍可使用邮箱验证码登录。'
            : '密码登录是可选方式；首次设置需要重新验证本人邮箱。'
        }}
      </p>
      <p v-if="!enabled" role="status">
        当前访问方式未开放密码设置。公网需 HTTPS，验证码登录仍按站点配置提供。
      </p>
      <form v-else @submit.prevent="save">
        <template v-if="!status.configured">
          <p>
            验证邮箱：<span class="password-email">{{ email }}</span>
          </p>
          <button
            type="button"
            class="button secondary"
            data-test="send-password-code"
            :disabled="busy || !session.ready || !!wait"
            @click="send"
          >
            {{ wait ? `${wait} 秒后可重发` : challenge ? '重新发送验证码' : '发送设置密码验证码' }}
          </button>
          <template v-if="challenge">
            <label for="password-code">6 位验证码</label>
            <input
              id="password-code"
              v-model="code"
              type="text"
              autocomplete="one-time-code"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              :disabled="busy || !session.ready || expired"
              required
            />
            <p v-if="expired" role="status">验证码已过期，请重新发送。</p>
          </template>
        </template>
        <template v-else>
          <label for="current-password">原密码</label>
          <input
            id="current-password"
            v-model="currentPassword"
            :type="show ? 'text' : 'password'"
            autocomplete="current-password"
            maxlength="128"
            :disabled="busy || !session.ready"
            required
          />
        </template>
        <label for="new-password">新密码</label>
        <input
          id="new-password"
          v-model="newPassword"
          :type="show ? 'text' : 'password'"
          autocomplete="new-password"
          maxlength="128"
          :disabled="busy || !session.ready"
          aria-describedby="password-help"
          required
        />
        <p id="password-help" class="muted">
          15–64 个字符，UTF-8 不超过 72 字节。支持空格，请勿使用其他网站的密码。
        </p>
        <label for="confirm-password">确认新密码</label>
        <input
          id="confirm-password"
          v-model="confirmPassword"
          :type="show ? 'text' : 'password'"
          autocomplete="new-password"
          maxlength="128"
          :disabled="busy || !session.ready"
          required
        />
        <p v-if="confirmPassword && newPassword !== confirmPassword" role="status">
          两次输入的新密码不一致。
        </p>
        <p v-if="newPassword && !passwordValid(newPassword)" role="status">
          请检查密码长度与字节限制。
        </p>
        <div class="account-send-row">
          <button type="button" class="button secondary" :aria-pressed="show" @click="show = !show">
            {{ show ? '隐藏密码' : '显示密码' }}
          </button>
          <button type="submit" class="button primary" :disabled="busy || !session.ready || !valid">
            {{ busy ? '正在保存…' : status.configured ? '修改密码' : '设置密码' }}
          </button>
        </div>
      </form>
    </template>
    <p v-if="error" class="account-error" role="alert">{{ error }}</p>
    <button v-if="!status && !loading" type="button" class="button secondary" @click="load">
      重试读取密码设置
    </button>
    <p v-if="notice" class="account-notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped src="../account.css"></style>
<style scoped>
.password-settings {
  margin-top: 24px;
}
.password-email {
  overflow-wrap: anywhere;
}
</style>
