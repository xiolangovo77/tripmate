<script setup>
import { onMounted, ref, watch } from 'vue'
import { messageApi } from '../api/message.js'
import { dateTime } from '../utils/format.js'
import AsyncState from './AsyncState.vue'
const props = defineProps({
  tripId: { type: String, required: true },
  busy: Boolean,
  canApply: Boolean,
})
const emit = defineEmits(['apply', 'ignored'])
const items = ref([])
const status = ref('pending')
const selected = ref([])
const loading = ref(false)
const ignoring = ref(false)
const error = ref('')
const actionError = ref('')
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  selected.value = []
  try {
    items.value = await messageApi.suggestions(props.tripId, status.value)
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}
function apply() {
  emit(
    'apply',
    items.value
      .filter((item) => selected.value.includes(item.id))
      .map(({ id, content, createTime }) => ({ id, content, createTime })),
  )
}
async function ignore() {
  if (!selected.value.length || ignoring.value || props.busy) return
  ignoring.value = true
  actionError.value = ''
  try {
    await messageApi.ignore(props.tripId, selected.value)
    await load()
    emit('ignored')
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    ignoring.value = false
  }
}
watch(status, load)
onMounted(load)
defineExpose({ load })
</script>
<template>
  <section class="card card-pad">
    <div class="row between wrap">
      <h2 class="h3">AI 修改建议</h2>
      <button class="text-btn tiny" :disabled="loading || busy || ignoring" @click="load">
        刷新建议
      </button>
    </div>
    <p class="tiny muted">选择想采纳的建议，AI 将结合当前行程进行调整。</p>
    <label class="label" for="suggestion-status">建议状态</label
    ><select
      id="suggestion-status"
      v-model="status"
      class="field mb"
      :disabled="busy || loading || ignoring"
    >
      <option value="pending">待处理</option>
      <option value="applied">已应用</option>
      <option value="ignored">已忽略</option></select
    ><AsyncState
      :loading="loading"
      :error="error"
      :empty="!items.length"
      empty-text="此状态下暂无 AI 建议"
      @retry="load"
      ><div class="stack">
        <label v-for="item in items" :key="item.id" class="suggestion"
          ><input
            v-if="status === 'pending'"
            v-model="selected"
            type="checkbox"
            :value="item.id"
            :disabled="busy || ignoring"
          /><span class="grow"
            ><span class="tiny muted">{{ dateTime(item.createTime) }}</span
            ><span class="suggestion-content">{{ item.content }}</span></span
          ></label
        >
      </div>
      <div v-if="status === 'pending'" class="actions-row">
        <button
          class="btn"
          :disabled="!canApply || busy || ignoring || !selected.length"
          @click="apply"
        >
          {{ busy ? '正在更新行程…' : `采纳并更新行程（${selected.length}）` }}</button
        ><button
          class="btn btn-line"
          :disabled="busy || ignoring || !selected.length"
          @click="ignore"
        >
          {{ ignoring ? '处理中…' : '忽略所选建议' }}
        </button>
      </div>
      <p v-if="!canApply && status === 'pending'" class="tiny muted">
        先生成行程，再采纳修改建议。
      </p></AsyncState
    >
    <p v-if="actionError" class="err" role="alert">{{ actionError }}</p>
  </section>
</template>
