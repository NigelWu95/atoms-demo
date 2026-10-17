<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiFailure, errorMessage } from '../api/client'
import { emailMethods, sendEmailCode, type EmailChallenge } from '../api/emailAuth'
import { getProject } from '../api/projects'
import { accountReturnTarget } from '../runtime/accountReturn'
import { useDraftStore } from '../stores/draft'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const drafts = useDraftStore()
const route = useRoute(),
  router = useRouter()
const returnTarget = computed(() => accountReturnTarget(route.query.returnTo))
const backTo = computed(() => returnTarget.value?.path ?? '/')
const retryReturn = ref(false)
const mode = ref<'code' | 'password'>('code'),
  password = ref(''),
  passwordEnabled = ref(false)
const enabled = ref<boolean | null>(null),
  loading = ref(false),
  busy = ref(false)
const email = ref(''),
  code = ref(''),
  error = ref(''),
  notice = ref('')
const challenge = ref<EmailChallenge | null>(null),
  challengeEmail = ref(''),
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
    if (mounted) {
      enabled.value = value.emailCodeEnabled
      passwordEnabled.value = value.passwordEnabled
    }
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
    challengeEmail.value = email.value.trim()
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
    (mode.value === 'code'
      ? !challenge.value || expired.value || !/^\d{6}$/.test(code.value)
      : !passwordEnabled.value || !emailValid.value || !password.value)
  )
    return
  busy.value = true
  error.value = ''
  notice.value = ''
  const origin = route.fullPath,
    epoch = session.revision
  // Carry only deliberate homepage input in this tab, never project/recovery caches.
  const idea = ['/', '/create'].includes(returnTarget.value?.path ?? '') ? drafts.prompt : null
  try {
    const completed =
      mode.value === 'code'
        ? await session.login(challenge.value!.challengeId, code.value)
        : await session.loginPassword(email.value.trim(), password.value)
    if (
      !completed ||
      !mounted ||
      route.fullPath !== origin ||
      session.revision !== epoch + 1 ||
      !registered.value
    )
      return
    if (idea !== null) drafts.prompt = idea
    await returnToSource()
  } catch (failure) {
    if (mounted) error.value = errorMessage(failure)
  } finally {
    if (mounted) {
      busy.value = false
      code.value = ''
      password.value = ''
    }
  }
}
async function returnToSource() {
  if (!registered.value) return
  const origin = route.fullPath,
    epoch = session.revision
  const current = () =>
    mounted && route.fullPath === origin && session.revision === epoch && registered.value
  const target = returnTarget.value
  busy.value = true
  retryReturn.value = false
  error.value = ''
  notice.value = '登录成功，正在返回…'
  try {
    if (target?.projectId) await getProject(target.projectId)
    if (current())
      await router.replace(target?.path === '/' ? '/create' : (target?.path ?? '/projects'))
  } catch (failure) {
    if (!current()) return
    if (failure instanceof ApiFailure && failure.status === 404) {
      await router.replace({ path: '/projects', query: { notice: 'account-project-unavailable' } })
    } else {
      notice.value = ''
      error.value = `登录已成功，但暂时无法返回原页面。${errorMessage(failure)}`
      retryReturn.value = true
    }
  } finally {
    if (mounted) busy.value = false
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
  // Revalidation replaces the identity object even when the same user returns from their inbox.
  [() => session.revision, () => session.identity?.userId, () => session.identity?.isGuest],
  () => {
    email.value = ''
    password.value = ''
    code.value = ''
    challenge.value = null
    notice.value = ''
    retryReturn.value = false
    error.value = ''
  },
)
watch(mode, () => {
  password.value = ''
  code.value = ''
  error.value = ''
  notice.value = ''
  if (mode.value === 'code' && challenge.value && email.value.trim() !== challengeEmail.value)
    challenge.value = null
})
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
  <section class="account-page">
    <RouterLink :to="backTo" class="account-back">{{
      backTo === '/' ? '← 返回创作' : '← 返回原页面'
    }}</RouterLink>
    <p class="eyebrow">YOUR ACCOUNT</p>
    <h1 tabindex="-1">{{ registered ? '账号与工作空间' : '让灵感，有处可存' }}</h1>
    <p class="account-intro">用邮箱验证码或账号密码登录，在不同浏览器继续你的创作。</p>
    <div class="account-layout">
      <div class="account-card">
        <template v-if="registered">
          <span class="account-badge">邮箱账号 · 已登录</span>
          <h2>{{ session.identity?.displayName }}</h2>
          <p>
            项目、历史对话和应用版本归属当前账号。退出不会删除已保存的内容，也不会停止正在进行的生成。
          </p>
          <RouterLink to="/projects" class="button primary">回到我的项目</RouterLink>
          <RouterLink to="/settings/account" class="button secondary">管理账号与密码</RouterLink>
          <button
            v-if="retryReturn"
            class="button secondary"
            type="button"
            data-test="retry-return"
            :disabled="busy"
            @click="returnToSource"
          >
            重试返回原页面
          </button>
          <button class="button secondary" type="button" :disabled="busy" @click="logout">
            {{ busy ? '请稍候…' : '退出账号' }}
          </button>
        </template>
        <template v-else-if="enabled === false">
          <h2>邮箱登录暂未开放</h2>
          <p>
            管理员需要配置发信邮箱和站点访问方式。公网默认使用 HTTPS；临时 HTTP
            演示需由管理员显式开启，传输不加密。
          </p>
          <p>你仍可以用访客身份创作，当前浏览器的项目会保存在服务端。</p>
          <RouterLink to="/" class="button primary">继续访客创作</RouterLink>
        </template>
        <form v-else-if="enabled" @submit.prevent="verify">
          <h2>邮箱注册 / 登录</h2>
          <div class="login-options" role="group" aria-label="选择登录方式">
            <button
              type="button"
              class="button secondary"
              :aria-pressed="mode === 'code'"
              :disabled="busy"
              @click="mode = 'code'"
            >
              验证码登录
            </button>
            <button
              type="button"
              class="button secondary"
              :aria-pressed="mode === 'password'"
              :disabled="busy"
              @click="mode = 'password'"
            >
              密码登录
            </button>
          </div>
          <p id="email-help">
            {{
              mode === 'code'
                ? '首次验证会创建账号，已有邮箱会直接登录。'
                : '使用已设置密码的邮箱账号登录；未设置或忘记密码可使用验证码登录。'
            }}
          </p>
          <p class="muted">
            {{
              returnTarget
                ? '登录后返回刚才的页面；原项目需当前账号有访问权限。'
                : '登录后进入「我的项目」。'
            }}
          </p>
          <label for="account-email">邮箱地址</label>
          <input
            id="account-email"
            v-model="email"
            type="email"
            autocomplete="email"
            inputmode="email"
            maxlength="254"
            required
            :readonly="mode === 'code' && !!challenge"
            :disabled="busy || !session.ready"
            aria-describedby="email-help account-warning"
            placeholder="you@example.com"
          />
          <div v-if="mode === 'code'" class="account-send-row">
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
          <template v-if="mode === 'code' && challenge">
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
          <template v-if="mode === 'password'">
            <label for="account-password">账号密码</label>
            <input
              id="account-password"
              v-model="password"
              type="password"
              autocomplete="current-password"
              maxlength="128"
              required
              :disabled="busy || !session.ready || !passwordEnabled"
            />
            <p v-if="!passwordEnabled" role="status">
              当前访问方式未开放密码登录。公网需 HTTPS，你仍可切回验证码登录。
            </p>
            <button
              class="button primary account-submit"
              type="submit"
              :disabled="busy || !session.ready || !passwordEnabled || !emailValid || !password"
            >
              {{ busy ? '正在登录…' : '登录' }}
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
        <p v-if="!session.ready" class="muted">请等待会话连接；连接失败时可在页首重新连接。</p>
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

<style scoped src="../account.css"></style>
