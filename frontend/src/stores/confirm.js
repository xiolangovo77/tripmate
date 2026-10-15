import { defineStore } from 'pinia'
import { shallowRef } from 'vue'

export const useConfirmStore = defineStore('confirm', () => {
  const request = shallowRef(null)
  let resolve
  function ask(options) {
    if (request.value) return Promise.resolve(false)
    request.value = options
    return new Promise((done) => { resolve = done })
  }
  function answer(value) {
    request.value = null
    resolve?.(value)
    resolve = undefined
  }
  return { request, ask, answer }
})
