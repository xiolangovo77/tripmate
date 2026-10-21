<script setup>
import { nextTick, ref, watch } from 'vue'
import { useNoticeStore } from '../stores/notice.js'
const props = defineProps({ text: String, label: { type: String, default: '复制' } })
const manual = ref(false)
const copying = ref(false)
const control = ref(null)
const manualInput = ref(null)
const notice = useNoticeStore()
watch(() => props.text, () => { manual.value = false })

function copyWithSelection(text) {
  if (!control.value) return false
  const previous = document.activeElement
  const input = document.createElement('textarea')
  input.value = text
  input.readOnly = true
  input.tabIndex = -1
  input.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;font-size:16px;'
  // 邀请位于模态 dialog 中，挂到 body 会进入不可交互区域，无法聚焦和复制。
  control.value.appendChild(input)
  try {
    input.focus({ preventScroll: true })
    input.select()
    input.setSelectionRange(0, input.value.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    input.remove()
    if (previous?.isConnected) previous.focus({ preventScroll: true })
  }
}

async function copy() {
  if (!props.text || copying.value) return
  copying.value = true
  try {
    let copied = false
    if (window.isSecureContext && navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(props.text)
        copied = true
      } catch {
        // 权限受限时继续尝试兼容复制。
      }
    }
    if (!copied) copied = copyWithSelection(props.text)
    if (copied) {
      notice.show('已复制，发给朋友吧')
      manual.value = false
      return
    }
    manual.value = true
    await nextTick()
    manualInput.value?.focus({ preventScroll: true })
    manualInput.value?.select()
  } finally {
    copying.value = false
  }
}
</script>
<template>
  <div ref="control" class="copy-control">
    <button class="btn btn-line btn-sm" type="button" :disabled="!text || copying" @click="copy">
      {{ copying ? '复制中…' : label }}</button
    ><label v-if="manual" class="label"
      ><span role="status">浏览器未允许自动复制，请按 Ctrl+C / ⌘C，或长按下方文本复制</span><textarea
        ref="manualInput"
        class="field"
        readonly
        :value="text"
        @focus="$event.target.select()"
      />
    </label>
  </div>
</template>
