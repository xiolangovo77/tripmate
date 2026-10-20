<script setup>
import { computed, ref, toRef } from 'vue'
import { messageApi } from '../api/message.js'
import { useTripSuggestions } from '../composables/useTripSuggestions.js'
import { dateTime } from '../utils/format.js'
import AsyncState from './AsyncState.vue'
const props = defineProps({
  tripId: { type: String, required: true },
  events: { type: Object, required: true },
  busy: Boolean,
  canApply: Boolean,
})
const emit = defineEmits(['apply', 'ignored'])
const status = ref('pending')
const ignoring = ref(false)
const actionError = ref('')
const { items, selected, loading, refreshing, error, syncError, syncStatus, load } =
  useTripSuggestions({
    tripId: toRef(props, 'tripId'),
    status,
    events: props.events,
    paused: computed(() => props.busy || ignoring.value),
  })
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
    // 请求失败可能只是响应丢失，重新查询确认结果，不重发写请求。
    await load()
  } finally {
    ignoring.value = false
  }
}
defineExpose({ load })
</script>
<template>
  <section class="card card-pad">
    <div class="row between wrap">
      <h2 class="h3">AI 修改建议</h2>
      <button class="text-btn tiny" :disabled="loading || refreshing || busy || ignoring" @click="load">
        刷新建议
      </button>
    </div>
    <p class="tiny muted">选择想采纳的建议，AI 将结合当前行程进行调整。</p>
    <p class="tiny muted" role="status">{{ syncStatus }}</p>
    <p v-if="syncError" class="tiny err" role="alert">建议同步失败：{{ syncError }}</p>
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
