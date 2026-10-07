<script setup lang="ts">
import { ref } from 'vue'
import PromptComposer from '../components/PromptComposer.vue'
import { useDraftStore } from '../stores/draft'

const draft = useDraftStore()
const panel = ref<'conversation' | 'preview'>('conversation')
const view = ref<'preview' | 'code'>('preview')
const device = ref<'desktop' | 'mobile'>('desktop')
</script>

<template>
  <div class="workbench-page">
    <header class="workbench-header">
      <div>
        <RouterLink to="/" class="subtle-link">← 返回创作</RouterLink>
        <h1 tabindex="-1">需求草稿 <span class="outline-badge">未保存</span></h1>
      </div>
      <button class="button primary" type="button" disabled title="应用生成后才能发布">
        发布应用 ↗
      </button>
    </header>
    <p class="workbench-notice">
      基础工作台已就绪。尚未创建项目、调用模型或生成代码；草稿仅在当前页面会话中保留。
    </p>
    <div class="mobile-view-controls" role="group" aria-label="工作台区域">
      <button
        type="button"
        :aria-pressed="panel === 'conversation'"
        @click="panel = 'conversation'"
      >
        对话</button
      ><button type="button" :aria-pressed="panel === 'preview'" @click="panel = 'preview'">
        预览
      </button>
    </div>
    <div class="workbench-grid" :class="{ 'mobile-preview': panel === 'preview' }">
      <section class="conversation-panel" aria-labelledby="conversation-title">
        <div class="panel-heading">
          <h2 id="conversation-title">需求与对话</h2>
          <span class="muted">尚未开始生成</span>
        </div>
        <div class="conversation-body">
          <div class="agent-avatar" aria-hidden="true">✳</div>
          <h3>先把想法说清楚</h3>
          <p>你希望谁来使用这个应用？它需要完成什么？可以继续完善下方的需求。</p>
          <div class="workflow-explainer">
            <span>01 理解需求</span><span>02 构建应用</span><span>03 校验与预览</span>
          </div>
          <p class="muted">这是计划中的工作流程，不是正在执行的任务。</p>
        </div>
        <PromptComposer
          v-model="draft.prompt"
          disabled
          label="继续完善你的需求"
          action-label="生成应用"
          hint="生成能力尚未接入"
        />
      </section>
      <section class="preview-panel" aria-labelledby="preview-title">
        <div class="preview-toolbar">
          <div role="group" aria-label="产物视图">
            <button type="button" :aria-pressed="view === 'preview'" @click="view = 'preview'">
              预览</button
            ><button type="button" :aria-pressed="view === 'code'" @click="view = 'code'">
              代码
            </button>
          </div>
          <div role="group" aria-label="预览尺寸">
            <button type="button" :aria-pressed="device === 'desktop'" @click="device = 'desktop'">
              桌面</button
            ><button type="button" :aria-pressed="device === 'mobile'" @click="device = 'mobile'">
              手机
            </button>
          </div>
        </div>
        <div class="preview-stage">
          <div class="preview-canvas" :class="{ 'mobile-canvas': device === 'mobile' }">
            <div class="browser-decoration" aria-hidden="true"><i /><i /><i /></div>
            <div class="preview-empty">
              <span class="preview-icon" aria-hidden="true">{{
                view === 'preview' ? '◫' : '⌘'
              }}</span>
              <h2 id="preview-title">
                {{ view === 'preview' ? '还没有生成应用' : '还没有可查看的源码' }}
              </h2>
              <p>
                {{
                  view === 'preview'
                    ? '生成完成后，你可以在这里直接使用应用。'
                    : '真实生成的 HTML、CSS 和 JavaScript 将显示在这里。'
                }}
              </p>
              <span class="outline-badge">等待生成能力接入</span>
            </div>
          </div>
        </div>
        <footer class="preview-status">
          <span><span class="neutral-dot" aria-hidden="true" />暂无产物</span
          ><span>未执行预览检查</span>
        </footer>
      </section>
    </div>
  </div>
</template>
