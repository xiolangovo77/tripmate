<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  src: String,
  name: String,
  size: { type: Number, default: 44 },
})
const failed = ref(false)
watch(
  () => props.src,
  () => {
    failed.value = false
  },
)
</script>

<template>
  <span class="user-avatar" :style="{ width: `${size}px`, height: `${size}px` }">
    <img v-if="src && !failed" :src="src" :alt="`${name || '用户'}的头像`" @error="failed = true" />
    <span v-else aria-hidden="true">{{ (name || '旅').slice(0, 1) }}</span>
  </span>
</template>

<style scoped>
.user-avatar {
  display: inline-grid;
  place-items: center;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: 50%;
  background: #d7e1c9;
  color: #4a6337;
  font-size: 18px;
}
.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
