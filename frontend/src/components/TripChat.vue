<script setup>
import { computed, nextTick, ref, toRef, watch, useId } from 'vue'
import { messageApi } from '../api/message.js'
import { itineraryApi } from '../api/itinerary.js'
import { useTripMessages } from '../composables/useTripMessages.js'
import { useTripSnapshot } from '../composables/useTripSnapshot.js'
import { useAuthStore } from '../stores/auth.js'
import UserAvatar from './UserAvatar.vue'
import { asId } from '../utils/json.js'
import { changeNames, dateTime, mentionsAi, sameId } from '../utils/format.js'
const props = defineProps({
  tripId: { type: String, required: true },
  events: { type: Object, required: true },
  members: { type: Array, default: () => [] },
  itineraryBusy: { type: Boolean, default: false },
})
const emit = defineEmits(['changed'])
const auth = useAuthStore()
const text = ref('')
const sending = ref(false)
const aiBusy = ref(false)
const sendError = ref('')
const needsItinerary = ref(false)
const log = ref(null)
const {
  messages,
  replySources,
  referenceError,
  loading,
  error,
  hasMore,
  refresh,
  loadOlder,
  syncStatus,
} = useTripMessages({
  tripId: toRef(props, 'tripId'),
  events: props.events,
  log,
})
const retryAiId = ref(null)
const stage = ref('')
const composer = ref(null)
const composerId = useId()
const withAi = computed(() => mentionsAi(text.value))
const remoteItineraryBusy = ref(false)
const itineraryBusyHint = '行程更新中，暂时无法 @AI，请稍后再试。普通聊天仍可发送。'
const itineraryBusy = computed(() => props.itineraryBusy || remoteItineraryBusy.value)
const busySnapshot = useTripSnapshot({
  tripId: toRef(props, 'tripId'),
  events: props.events,
  eventName: 'trip.itinerary.updating',
  read: (id, signal) => itineraryApi.busy(id, { signal }),
  apply: (busy) => {
    remoteItineraryBusy.value = busy === true
  },
  reset: () => {
    remoteItineraryBusy.value = false
  },
})
// 更新开始和结束通过 SSE 触发查询，复用共享连接的重连及补查机制。

async function canAskAi() {
  if (props.itineraryBusy) return false
  const synced = await busySnapshot.refresh()
  if (!synced) {
    sendError.value = '暂时无法确认行程状态，请稍后重试；普通聊天不受影响。'
    return false
  }
  return !itineraryBusy.value
}
const chatMessages = computed(() =>
  messages.value.map((message) => ({
    ...message,
    replySource: message.replyToId ? replySources.value.get(String(message.replyToId)) : null,
  })),
)
function fillPrompt(prompt) {
  if (itineraryBusy.value && mentionsAi(prompt)) return
  text.value = text.value.trim() ? `${text.value}\n${prompt}` : prompt
  nextTick(() => composer.value?.focus())
}
function mentionAi() {
  if (itineraryBusy.value) return
  text.value = mentionsAi(text.value) ? text.value : '@AI ' + text.value
  composer.value?.focus()
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
function sender(message) {
  if (message.role === 'assistant') return '✦ AI 旅行搭子'
  return (
    (sameId(message.senderId, auth.user?.id) ? auth.user?.nickname : '') ||
    message.senderNickname ||
    props.members.find((member) => sameId(member.userId, message.senderId))?.nickname ||
    '未知成员'
  )
}
function senderAvatar(message) {
  if (sameId(message.senderId, auth.user?.id)) return auth.user?.avatarUrl || ''
  return message.senderAvatarUrl || ''
}
async function askAi(id) {
  if (aiBusy.value) return
  aiBusy.value = true
  try {
    // 保存消息后可能刚好开始更新行程，再次检查；保留原消息 ID，恢复后可重试。
    if (!(await canAskAi())) {
      retryAiId.value = id
      sendError.value = itineraryBusy.value
        ? '你的消息已保存。行程更新中，完成后可重试 AI 回复。'
        : sendError.value
      return
    }
    retryAiId.value = null
    needsItinerary.value = false
    stage.value = 'AI 正在整理想法，请稍候…'
    await messageApi.ai(props.tripId, id)
    emit('changed')
  } catch (cause) {
    if (cause.message === '行程不存在') {
      needsItinerary.value = true
    } else {
      retryAiId.value = id
      sendError.value = `你的消息已保存。AI 暂时未能回复：${cause.message}`
    }
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
  needsItinerary.value = false
  retryAiId.value = null
  try {
    // 手动输入 @AI 或使用键盘快捷发送也需要检查，拦截时保留输入内容。
    if (mentionsAi(content) && !(await canAskAi())) return
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
  if (aiBusy.value || sending.value || itineraryBusy.value || !retryAiId.value) return
  const id = retryAiId.value
  sendError.value = ''
  // 超时可能已经落库，先查询回复，避免盲目重发用户消息或 AI 请求。
  const synced = await refresh()
  if (!synced) {
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
    <p class="tiny muted">@AI 会结合旅行信息和已有的问答记忆回复。</p>
    <p class="tiny muted" role="status">{{ syncStatus }}</p>
    <div v-if="error" class="err" role="alert">{{ error }}</div>
    <div
      ref="log"
      class="chat-log"
      role="log"
      aria-label="旅行历史消息"
      aria-live="polite"
      tabindex="0"
    >
      <button v-if="hasMore" class="text-btn tiny" :disabled="loading" @click="loadOlder">
        加载更早的消息
      </button>
      <div v-if="!messages.length" class="chat-empty">
        <span aria-hidden="true">✦</span
        ><strong>{{ loading ? '正在读取消息…' : '你的想法，是旅行的第一站。' }}</strong>
        <p class="tiny muted">
          {{ loading ? '马上就好' : '聊聊想吃的、想看的，或者让 AI 一起想。' }}
        </p>
      </div>
      <div
        v-for="message in chatMessages"
        :key="message.id"
        class="chat-message-row"
        :class="{
          'is-self': message.role !== 'assistant' && sameId(message.senderId, auth.user?.id),
        }"
      >
        <span
          v-if="message.role === 'assistant'"
          class="chat-ai-avatar"
          role="img"
          aria-label="AI 旅行搭子头像"
          >✦</span
        >
        <UserAvatar v-else :src="senderAvatar(message)" :name="sender(message)" :size="36" />
        <article
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
          <blockquote
            v-if="
              message.role === 'assistant' &&
              message.replyToId &&
              message.recallStatus !== 'recalled'
            "
            class="chat-reply"
            aria-label="回复的原消息"
          >
            <strong class="chat-reply-author">{{
              message.replySource ? `回复 ${sender(message.replySource)}` : '回复原消息'
            }}</strong>
            <div class="chat-reply-content" tabindex="0">
              {{
                message.replySource
                  ? message.replySource.recallStatus === 'recalled'
                    ? '原消息已撤回'
                    : message.replySource.content
                  : loading
                    ? '正在加载原消息…'
                    : referenceError || '原消息已不存在或无法查看'
              }}
            </div>
          </blockquote>
          <div>{{ message.recallStatus === 'recalled' ? '消息已撤回' : message.content }}</div>
          <span
            v-if="message.role === 'assistant' && message.changeStatus !== 'none'"
            class="chat-status"
            >{{ changeNames[message.changeStatus] || message.changeStatus }}</span
          >
        </article>
      </div>
    </div>
    <div v-if="stage" class="callout" role="status"><span class="spinner" /> {{ stage }}</div>
    <div v-if="itineraryBusy" class="callout" role="status">{{ itineraryBusyHint }}</div>
    <div v-if="needsItinerary" class="callout chat-feedback" role="status">
      <strong>先生成行程，再和 AI 聊聊吧</strong>
      <span
        >你的消息已保存。当前旅行还没有行程，请由发起人在「AI 行程」页面生成后，再 @AI 提问。</span
      >
      <RouterLink class="text-btn" :to="`/trips/${tripId}/itinerary`">前往 AI 行程 →</RouterLink>
    </div>
    <div v-if="sendError" class="err chat-feedback" role="alert">
      <span>{{ sendError }}</span>
      <button
        v-if="retryAiId"
        class="text-btn"
        :disabled="aiBusy || sending || loading || itineraryBusy"
        @click="retryAi"
      >
        重试 AI 回复
      </button>
    </div>
    <div v-if="!messages.length && !loading" class="chat-prompts">
      <button
        v-for="prompt in ['大家有什么特别想去的地方？', '@AI 帮我们安排轻松一点的路线']"
        :key="prompt"
        type="button"
        :disabled="sending || aiBusy || (itineraryBusy && mentionsAi(prompt))"
        @click="fillPrompt(prompt)"
      >
        {{ prompt }} ↗
      </button>
    </div>
    <form class="stack chat-composer" :class="{ 'with-ai': withAi }" @submit.prevent="send">
      <div class="row between">
        <label class="label" :for="composerId">说说你的想法</label
        ><span v-if="withAi" class="ai-indicator">{{
          itineraryBusy ? '✦ 行程更新中，AI 暂不可用' : '✦ AI 将参与回复'
        }}</span>
      </div>
      <textarea
        :id="composerId"
        ref="composer"
        v-model="text"
        class="field"
        :placeholder="
          itineraryBusy ? '行程更新中，可以先和旅行成员聊聊' : '例如：@AI 帮我们安排轻松一点的路线'
        "
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
          :disabled="sending || aiBusy || itineraryBusy"
          :title="itineraryBusy ? itineraryBusyHint : '让 AI 参与回复'"
          @click="mentionAi"
        >
          @AI</button
        ><span :id="`${composerId}-hint`" class="composer-hint">Ctrl / ⌘ + Enter 发送</span
        ><button
          class="btn btn-sm"
          :disabled="sending || aiBusy || !text.trim() || (withAi && itineraryBusy)"
        >
          {{ aiBusy ? '等待 AI…' : sending ? '发送中…' : '发送 ↗' }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.chat-message-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  flex-shrink: 0;
}
.chat-message-row.is-self {
  flex-direction: row-reverse;
}
.chat-message-row > .chat {
  min-width: 0;
  max-width: calc(100% - 46px);
  align-self: auto;
}
.chat-message-row > .chat.assistant {
  flex: 1;
}
.chat-message-row.is-self .chat-meta {
  flex-direction: row-reverse;
}
.chat-ai-avatar {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border: 1px solid #dce6cf;
  border-radius: 50%;
  background: #e7efdb;
  color: #46654e;
  font-size: 22px;
}
@media (max-width: 600px) {
  .chat-message-row {
    gap: 8px;
  }
  .chat-message-row > .chat {
    max-width: calc(100% - 44px);
    padding: 10px 12px;
  }
}
.chat-reply {
  margin: 8px 0 12px;
  padding: 8px 12px;
  border-left: 3px solid #7fa88d;
  border-radius: 0 8px 8px 0;
  background: rgba(255, 255, 255, 0.65);
  color: #496651;
  font-size: 12px;
  white-space: normal;
}
.chat-reply-author {
  display: block;
  margin-bottom: 4px;
}
.chat-reply-content {
  max-height: 8em;
  overflow-y: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.chat-feedback {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  line-height: 1.7;
}
</style>
