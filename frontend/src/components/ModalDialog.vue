<script setup>
import { onMounted, onUnmounted, ref, useId } from 'vue'
defineProps({ title: String })
const emit = defineEmits(['close'])
const dialog = ref(null)
const id = useId()
let previous
let previousOverflow
onMounted(() => {
  previous = document.activeElement
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  dialog.value.showModal()
})
onUnmounted(() => {
  document.body.style.overflow = previousOverflow
  if (previous?.isConnected) previous.focus({ preventScroll: true })
})
function outside(event) {
  if (event.target !== dialog.value) return
  const box = dialog.value.getBoundingClientRect()
  if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) emit('close')
}
</script>
<template>
  <dialog
    ref="dialog"
    class="native-dialog"
    :aria-labelledby="id"
    @cancel.prevent="emit('close')"
    @click="outside"
  >
    <div class="row between">
      <h2 :id="id" class="h3">{{ title }}</h2>
      <button class="text-btn" aria-label="关闭弹窗" @click="emit('close')">关闭 ×</button>
    </div>
    <div class="divider" />
    <slot />
  </dialog>
</template>
