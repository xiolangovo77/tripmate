<script setup>
import { ref, watch } from 'vue'
const props = defineProps({
  loading: Boolean,
  error: String,
  empty: Boolean,
  emptyText: { type: String, default: '暂时还没有内容' },
})
defineEmits(['retry'])
const ready = ref(false)
watch(
  () => [props.loading, props.error],
  () => {
    if (!props.loading && !props.error) ready.value = true
  },
  { immediate: true },
)
</script>
<template>
  <div class="async-region" :aria-busy="loading">
    <div v-if="loading && !ready" class="skeleton-card" role="status">
      <span class="sr-only">正在加载，请稍候…</span>
      <div class="skeleton skeleton-title" />
      <div class="skeleton" />
      <div class="skeleton" />
      <div class="skeleton skeleton-block" />
    </div>
    <div v-if="loading && ready" class="refresh-indicator" role="status">
      <span class="spinner" />正在更新…
    </div>
    <div v-if="error" class="error-state" role="alert">
      <p>{{ error }}</p>
      <button class="btn btn-line btn-sm" @click="$emit('retry')">重新加载</button>
    </div>
    <div v-if="ready" v-show="!error" :class="{ 'is-refreshing': loading }">
      <div v-if="empty" class="empty-state">
        <div class="illustration" aria-hidden="true">↗</div>
        <p class="muted">{{ emptyText }}</p>
        <slot name="empty" />
      </div>
      <slot v-else />
    </div>
  </div>
</template>
