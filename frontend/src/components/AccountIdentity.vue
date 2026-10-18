<script setup lang="ts">
import { ref, watch } from 'vue'
import { getAccount } from '../api/account'
import type { Identity } from '../api/session'

const props = defineProps<{ identity: Identity }>()
const email = ref('')
const failed = ref(false)
const retry = ref(0)
watch(
  [() => props.identity.userId, () => props.identity.isGuest, retry],
  async ([owner, guest], _, onCleanup) => {
    email.value = ''
    failed.value = false
    const controller = new AbortController()
    onCleanup(() => controller.abort())
    if (guest) return
    try {
      const profile = await getAccount(controller.signal)
      if (controller.signal.aborted) return
      if (profile.userId !== owner || profile.isGuest || !profile.email) throw new Error()
      email.value = profile.email
    } catch {
      if (!controller.signal.aborted) failed.value = true
    }
  },
  { immediate: true, flush: 'sync' },
)
</script>

<template>
  <span v-if="identity.isGuest" class="identity-name">{{ identity.displayName }}</span>
  <details v-else-if="email" class="account-identity">
    <summary :title="email" aria-label="查看完整账号邮箱">
      <span class="identity-name">{{ email }}</span>
    </summary>
    <span class="full-email">{{ email }}</span>
  </details>
  <button v-else-if="failed" class="text-button" type="button" @click="retry++">
    重试读取邮箱
  </button>
  <span v-else class="muted">正在读取邮箱…</span>
</template>

<style scoped>
.account-identity {
  min-width: 0;
  max-width: 100%;
}
summary {
  cursor: pointer;
  display: flex;
  align-items: center;
  min-height: 44px;
  list-style: none;
}
.identity-name {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.full-email {
  display: block;
  color: var(--muted);
  overflow-wrap: anywhere;
}
summary:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}
</style>
