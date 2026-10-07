<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Artifact } from '../api/artifacts'
const props = defineProps<{ artifact: Artifact }>()
const files = ['view.html', 'styles.css', 'main.js', 'manifest.json'] as const
const file = ref<(typeof files)[number]>('view.html')
const content = computed(() =>
  file.value === 'manifest.json'
    ? JSON.stringify(props.artifact.manifest, null, 2)
    : props.artifact.files[file.value],
)
</script>

<template>
  <div class="source-viewer">
    <div class="source-files" role="group" aria-label="源码文件">
      <button
        v-for="name in files"
        :key="name"
        type="button"
        :aria-pressed="file === name"
        @click="file = name"
      >
        {{ name }}
      </button>
    </div>
    <p class="muted">只读源码 · {{ file }}</p>
    <pre tabindex="0" :aria-label="file + ' 源码'"><code>{{ content }}</code></pre>
  </div>
</template>
