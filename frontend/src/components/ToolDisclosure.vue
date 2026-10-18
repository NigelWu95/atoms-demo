<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
defineProps<{ label: string }>()
const root = ref<HTMLDetailsElement | null>(null)
function close(restoreFocus = false) {
  if (!root.value?.open) return
  root.value.open = false
  if (restoreFocus) root.value.querySelector('summary')?.focus()
}
function outside(event: PointerEvent) {
  if (event.target instanceof Node && !root.value?.contains(event.target)) close()
}
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <details ref="root" class="tool-disclosure" @keydown.esc.stop.prevent="close(true)">
    <summary>
      <span>{{ label }}</span> <span aria-hidden="true">⌄</span>
    </summary>
    <div class="tool-content" role="region" :aria-label="label" tabindex="0"><slot /></div>
  </details>
</template>

<style scoped>
summary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 8px;
  gap: 4px;
  cursor: pointer;
  list-style: none;
  white-space: nowrap;
  color: var(--muted);
  border-radius: 6px;
  font-size: 12px;
}
details[open] > summary {
  background: var(--surface-raised);
  color: var(--text);
}
.tool-content {
  position: absolute;
  inset-inline: 8px;
  top: 100%;
  z-index: 8;
  max-height: min(40dvh, 360px);
  overflow: auto;
  overscroll-behavior: contain;
  padding: 12px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 8px 24px #0006;
  font-size: 12px;
  overflow-wrap: anywhere;
}
summary:focus-visible,
.tool-content:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}
</style>
