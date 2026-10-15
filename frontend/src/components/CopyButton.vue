<script setup>
import { ref } from 'vue'
import { useNoticeStore } from '../stores/notice.js'
const props = defineProps({ text: String, label: { type: String, default: '复制' } })
const manual = ref(false)
const notice = useNoticeStore()
async function copy() {
  try {
    await navigator.clipboard.writeText(props.text)
    notice.show('已复制，发给朋友吧')
    manual.value = false
  } catch {
    manual.value = true
  }
}
</script>
<template>
  <div class="copy-control">
    <button class="btn btn-line btn-sm" type="button" :disabled="!text" @click="copy">
      {{ label }}</button
    ><label v-if="manual" class="label"
      >请选择并手动复制<textarea
        class="field"
        readonly
        :value="text"
        @focus="$event.target.select()"
      />
    </label>
  </div>
</template>
