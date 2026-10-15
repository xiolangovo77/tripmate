<script setup>
import { computed, nextTick, onMounted, ref, watch, useId } from 'vue'
import { messageApi } from '../api/message.js'
import { useAuthStore } from '../stores/auth.js'
import { asId } from '../utils/json.js'
import { changeNames, dateTime, mentionsAi, sameId } from '../utils/format.js'
const props = defineProps({
  tripId: { type: String, required: true },
  members: { type: Array, default: () => [] },
})
const emit = defineEmits(['changed'])
const auth = useAuthStore()
const messages = ref([])
const text = ref('')
const loading = ref(false)
const sending = ref(false)
const aiBusy = ref(false)
const error = ref('')
const sendError = ref('')
const hasMore = ref(false)
const cursor = ref(null)
const log = ref(null)
const retryAiId = ref(null)
const stage = ref('')
const composer = ref(null)
const composerId = useId()
const withAi = computed(() => mentionsAi(text.value))
function fillPrompt(prompt) {
  text.value = text.value.trim() ? `${text.value}\n${prompt}` : prompt
  nextTick(() => composer.value?.focus())
}
function onKeydown(event) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
    event.preventDefault()
    send()
  }
}
watch(text, async () => {
  await nextTick()
  if (!composer.value) return
  composer.value.style.height = 'auto'
  composer.value.style.height = `${Math.min(composer.value.scrollHeight, 200)}px`
})
const sorted = computed(() =>
  [...messages.value].sort(
    (a, b) =>
      String(a.createTime).localeCompare(String(b.createTime)) ||
      String(a.id).localeCompare(String(b.id)),
  ),
)
function merge(incoming) {
  const byId = new Map(messages.value.map((item) => [String(item.id), item]))
  for (const item of incoming) byId.set(String(item.id), item)
  messages.value = [...byId.values()]
}
async function refresh(older = false) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  const oldHeight = log.value?.scrollHeight || 0
  const oldTop = log.value?.scrollTop || 0
  try {
    const batch = await messageApi.page(props.tripId, older ? cursor.value : undefined)
    if (older) merge(batch)
    else messages.value = batch
    // 刷新从最新一页重新建立游标，避免两次刷新间超过一页消息时漏掉中间记录。
    hasMore.value = batch.length === 30
    cursor.value =
      batch
        .map((item) => item.createTime)
        .filter(Boolean)
        .sort()[0] || null
    await nextTick()
    if (log.value)
      log.value.scrollTop = older ? log.value.scrollHeight - oldHeight + oldTop : log.value.scrollHeight
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}
function sender(message) {
  if (message.role === 'assistant') return '✦ AI 旅行搭子'
  return (
    props.members.find((member) => sameId(member.userId, message.senderId))?.nickname ||
    `成员 ${message.senderId}`
  )
}
async function askAi(id) {
  aiBusy.value = true
  retryAiId.value = null
  stage.value = 'AI 正在整理想法，请稍候…'
  try {
    await messageApi.ai(props.tripId, id)
    emit('changed')
  } catch (cause) {
    retryAiId.value = id
    sendError.value = `你的消息已保存。AI 请求未完成：${cause.message}`
  } finally {
    aiBusy.value = false
    stage.value = ''
    await refresh()
  }
}
async function send() {
  const content = text.value.trim()
  if (!content || sending.value || aiBusy.value) return
  sending.value = true
  sendError.value = ''
  try {
    const id = asId(await messageApi.add(props.tripId, content))
    text.value = ''
    // 普通消息绝不调用 AI；replyToId 必须使用已保存的用户消息 ID。
    await refresh()
    if (mentionsAi(content)) await askAi(id)
  } catch (cause) {
    sendError.value = cause.message
  } finally {
    sending.value = false
    await nextTick()
    composer.value?.focus({ preventScroll: true })
  }
}
async function retryAi() {
  if (aiBusy.value || sending.value || !retryAiId.value) return
  const id = retryAiId.value
  sendError.value = ''
  // 超时可能已经落库，先查询回复，避免盲目重发用户消息或 AI 请求。
  await refresh()
  if (error.value) {
    sendError.value = '暂时无法核对回复，请恢复连接后重试'
    return
  }
  if (messages.value.some((item) => item.role === 'assistant' && sameId(item.replyToId, id))) {
    retryAiId.value = null
    emit('changed')
    return
  }
  await askAi(id)
}
onMounted(() => refresh())
defineExpose({ refresh })
</script>
<template>
  <section id="trip-chat" class="card card-pad chat-panel">
    <div class="row between">
      <h2 class="h3">✦ 旅行群聊</h2>
      <button class="text-btn tiny" :disabled="loading || sending || aiBusy" @click="refresh()">
        {{ loading ? '加载中…' : '刷新消息' }}
      </button>
    </div>
    <p class="tiny muted">好行程，从聊聊各自的期待开始。</p>
    <div v-if="error" class="err" role="alert">{{ error }}</div>
    <div ref="log" class="chat-log" role="log" aria-label="旅行历史消息" aria-live="polite" tabindex="0">
      <button v-if="hasMore" class="text-btn tiny" :disabled="loading" @click="refresh(true)">
        加载更早的消息
      </button>
      <div v-if="!messages.length" class="chat-empty"><span aria-hidden="true">✦</span><strong>{{ loading ? '正在读取消息…' : '你的想法，是旅行的第一站。' }}</strong><p class="tiny muted">{{ loading ? '马上就好' : '聊聊想吃的、想看的，或者让 AI 一起想。' }}</p></div>
      <article
        v-for="message in sorted"
        :key="message.id"
        class="chat"
        :class="
          message.role === 'assistant'
            ? 'assistant'
            : sameId(message.senderId, auth.user?.id)
              ? 'user'
              : 'peer'
        "
      >
        <div class="chat-meta">
          <strong>{{ sender(message) }}</strong
          ><time>{{ dateTime(message.createTime) }}</time>
        </div>
        <div>{{ message.recallStatus === 'recalled' ? '消息已撤回' : message.content }}</div>
        <span
          v-if="message.role === 'assistant' && message.changeStatus !== 'none'"
          class="chat-status"
          >{{ changeNames[message.changeStatus] || message.changeStatus }}</span
        >
      </article>
    </div>
    <div v-if="stage" class="callout" role="status"><span class="spinner" /> {{ stage }}</div>
    <div v-if="sendError" class="err" role="alert">
      {{ sendError
      }}<button
        v-if="retryAiId"
        class="text-btn"
        :disabled="aiBusy || sending || loading"
        @click="retryAi"
      >
        核对并重试 AI
      </button>
    </div>
    <div v-if="!messages.length && !loading" class="chat-prompts"><button v-for="prompt in ['大家有什么特别想去的地方？', '@AI 帮我们安排轻松一点的路线']" :key="prompt" type="button" :disabled="sending || aiBusy" @click="fillPrompt(prompt)">{{ prompt }} ↗</button></div>
    <form class="stack chat-composer" :class="{ 'with-ai': withAi }" @submit.prevent="send">
      <div class="row between"><label class="label" :for="composerId">说说你的想法</label><span v-if="withAi" class="ai-indicator">✦ AI 将参与回复</span></div>
      <textarea
        :id="composerId"
        ref="composer"
        v-model="text"
        class="field"
        placeholder="例如：@AI 帮我们安排轻松一点的路线"
        maxlength="4000"
        required
        :disabled="sending || aiBusy"
        @keydown="onKeydown"
        :aria-describedby="`${composerId}-hint`"
      />
      <div class="row between">
        <button
          class="btn btn-soft btn-sm"
          type="button"
          :disabled="sending || aiBusy"
          @click="text = mentionsAi(text) ? text : '@AI ' + text; composer?.focus()"
        >
          @AI</button><span :id="`${composerId}-hint`" class="composer-hint">Ctrl / ⌘ + Enter 发送</span
        ><button class="btn btn-sm" :disabled="sending || aiBusy || !text.trim()">
          {{ aiBusy ? '等待 AI…' : sending ? '发送中…' : '发送 ↗' }}
        </button>
      </div>
    </form>
  </section>
</template>
