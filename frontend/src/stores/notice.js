import { defineStore } from 'pinia'
import { ref } from 'vue'
export const useNoticeStore = defineStore('notice', () => {
  const message = ref('')
  let timer
  function show(text) {
    message.value = text
    clearTimeout(timer)
    timer = setTimeout(() => {
      message.value = ''
    }, 4500)
  }
  return { message, show }
})
