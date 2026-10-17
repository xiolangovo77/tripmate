<script setup>
import { computed, onMounted, ref } from 'vue'
import { invitationApi } from '../api/invitation.js'
import { newInviteCode, dateTime } from '../utils/format.js'
import { useNoticeStore } from '../stores/notice.js'
import AsyncState from './AsyncState.vue'
import CopyButton from './CopyButton.vue'
import { useConfirmStore } from '../stores/confirm.js'
const confirmation = useConfirmStore()
const props = defineProps({ tripId: { type: String, required: true } })
const invitation = ref(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const actionError = ref('')
const validity = ref('')
const valid = ref(false)
const notice = useNoticeStore()
const link = computed(
  () =>
    `${window.location.origin}/invitation?${new URLSearchParams({ tripId: props.tripId, inviteCode: invitation.value?.inviteCode || '' })}`,
)
async function load() {
  loading.value = true
  error.value = ''
  valid.value = false
  try {
    invitation.value = await invitationApi.get(props.tripId)
    if (invitation.value) {
      try {
        await invitationApi.check(props.tripId, invitation.value.inviteCode)
        valid.value = true
        validity.value = '邀请有效'
      } catch (cause) {
        validity.value = cause.message
      }
    }
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}
async function create() {
  if (
    busy.value ||
    (invitation.value &&
      !(await confirmation.ask({
        title: '换一张新的邀请？',
        message: '更新后，之前分享的邀请码和链接将失效。已加入的伙伴不受影响。',
        confirmText: '更新邀请码',
      })))
  )
    return
  busy.value = true
  actionError.value = ''
  try {
    await invitationApi.create(props.tripId, newInviteCode())
    notice.show('邀请码已创建')
    await load()
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    busy.value = false
  }
}
async function revoke() {
  if (
    busy.value ||
    !(await confirmation.ask({
      title: '收回这份邀请？',
      message: '撤销后，朋友将无法通过这个链接加入。已加入的伙伴不受影响。',
      confirmText: '确认撤销',
      danger: true,
    }))
  )
    return
  busy.value = true
  actionError.value = ''
  try {
    await invitationApi.revoke(props.tripId, invitation.value.inviteCode)
    notice.show('邀请已撤销')
    await load()
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <AsyncState :loading="loading" :error="error" @retry="load"
    ><div class="stack">
      <p class="muted tiny">
        同一个邀请码可以分享给多位朋友。每位朋友登录后填写自己的预算，再确认加入。
      </p>
      <template v-if="invitation"
        ><div class="invite-code-box">
          <span class="tiny muted">朋友的入场券</span><strong>{{ invitation.inviteCode }}</strong
          ><span class="tiny">有效至 {{ dateTime(invitation.expiresTime) }}</span>
        </div>
        <div :class="valid ? 'callout' : 'notice'">{{ validity }}</div>
        <div class="link-code">{{ link }}</div>
        <div v-if="valid" class="row wrap">
          <CopyButton :text="invitation.inviteCode" label="复制邀请码" /><CopyButton
            :text="link"
            label="复制邀请链接 ↗"
          /><RouterLink
            class="text-btn tiny"
            :to="{ path: '/invitation', query: { tripId, inviteCode: invitation.inviteCode } }"
            >预览邀请</RouterLink
          >
        </div></template
      >
      <p v-else class="callout">还没有有效邀请码，创建一份邀请吧。</p>
      <p v-if="actionError" class="err" role="alert">{{ actionError }}</p>
      <div class="row wrap">
        <button class="btn" :disabled="busy" @click="create">
          {{ busy ? '处理中…' : invitation ? '更新邀请码' : '创建邀请码' }}</button
        ><button v-if="invitation" class="btn btn-line" :disabled="busy" @click="revoke">
          撤销邀请
        </button>
      </div>
    </div></AsyncState
  >
</template>
