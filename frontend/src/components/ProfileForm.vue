<script setup>
import { onUnmounted, reactive, ref, useId, watch } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { useNoticeStore } from '../stores/notice.js'
import { commonApi } from '../api/common.js'
import { parseActivities } from '../utils/format.js'
import PreferenceFields from './PreferenceFields.vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  submitLabel: { type: String, default: '保存个人信息与偏好' },
  allowSkip: Boolean,
  disabled: Boolean,
})
const emit = defineEmits(['saved', 'skip', 'busy-change'])
const auth = useAuthStore()
const notice = useNoticeStore()
const id = useId()
const initialActivities = parseActivities(auth.user?.favoriteActivities)
const profile = reactive({
  nickname: auth.user?.nickname || '无名',
  avatarUrl: auth.user?.avatarUrl || '',
  travelPace: auth.user?.travelPace || 'balanced',
  activities: initialActivities.length ? initialActivities : ['美食', '自然', '拍照'],
})
const error = ref('')
const saving = ref(false)
const saved = ref(false)
const uploading = ref(false)
const avatarFile = ref(null)
const avatarPreview = ref('')
const uploadedAvatarUrl = ref('')
const maxAvatarSize = 5 * 1024 * 1024

function clearAvatarDraft() {
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
  avatarPreview.value = ''
  avatarFile.value = null
  uploadedAvatarUrl.value = ''
}
function selectAvatar(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || saving.value || props.disabled) return
  error.value = ''
  if (!/\.(jpe?g|png|gif|webp|bmp)$/i.test(file.name) || !file.type.startsWith('image/')) {
    error.value = '请选择 JPG、PNG、GIF、WebP 或 BMP 图片'
    return
  }
  if (!file.size) {
    error.value = '不能上传空图片'
    return
  }
  if (file.size > maxAvatarSize) {
    error.value = '头像图片不能超过 5 MB，请压缩图片或选择更小的图片'
    return
  }
  clearAvatarDraft()
  avatarFile.value = file
  avatarPreview.value = URL.createObjectURL(file)
  saved.value = false
}
onUnmounted(clearAvatarDraft)
watch(
  profile,
  () => {
    saved.value = false
  },
  { flush: 'sync' },
)

async function save() {
  if (saving.value || props.disabled) return
  error.value = ''
  if (!profile.nickname.trim()) {
    error.value = '请填写昵称'
    return
  }
  if (!profile.activities.length) {
    error.value = '请至少选择一项活动偏好'
    return
  }
  if (!auth.user) {
    error.value = '登录已过期，请重新登录后保存'
    return
  }
  saving.value = true
  emit('busy-change', true)
  try {
    // 仅在保存时上传；资料保存失败后重试复用 URL，避免再次消耗上传额度。
    if (avatarFile.value && !uploadedAvatarUrl.value) {
      uploading.value = true
      const url = await commonApi.uploadImage(avatarFile.value)
      if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) {
        throw new Error('上传未返回有效的图片地址，请重试')
      }
      uploadedAvatarUrl.value = url
      uploading.value = false
    }
    await auth.update({
      userAccount: auth.user.userAccount,
      nickname: profile.nickname.trim(),
      avatarUrl: uploadedAvatarUrl.value || profile.avatarUrl,
      travelPace: profile.travelPace,
      favoriteActivities: JSON.stringify(profile.activities),
    })
    profile.avatarUrl = auth.user.avatarUrl || ''
    clearAvatarDraft()
    saved.value = true
    notice.show('个人信息和偏好已保存')
    emit('saved')
  } catch (cause) {
    error.value = cause.message
  } finally {
    uploading.value = false
    saving.value = false
    emit('busy-change', false)
  }
}
</script>

<template>
  <form class="stack" @submit.prevent="save">
    <fieldset class="stack profile-fields" :disabled="saving || disabled">
      <div>
        <span class="label">账号</span>
        <div class="field readonly-field">{{ auth.user?.userAccount }}</div>
      </div>
      <div>
        <label class="label" :for="`${id}-nickname`">昵称</label>
        <input
          :id="`${id}-nickname`"
          v-model="profile.nickname"
          class="field"
          required
          maxlength="50"
        />
      </div>
      <div>
        <label class="label" :for="`${id}-avatar`">修改头像</label>
        <div class="row">
          <UserAvatar
            :src="avatarPreview || profile.avatarUrl"
            :name="profile.nickname"
            :size="64"
          />
          <input
            :id="`${id}-avatar`"
            class="field avatar-file"
            type="file"
            accept=".jpg,.jpeg,.png,.gif,.webp,.bmp"
            :aria-describedby="`${id}-avatar-help`"
            @change="selectAvatar"
          />
        </div>
        <p :id="`${id}-avatar-help`" class="tiny muted">
          图片不超过 5 MB，每天最多修改 5 次，保存后生效。
        </p>
        <p v-if="avatarFile" class="tiny muted" role="status">
          已选择 {{ avatarFile.name }}，待保存。
        </p>
        <button v-if="avatarFile" class="text-btn tiny" type="button" @click="clearAvatarDraft">
          取消头像更换
        </button>
      </div>
      <PreferenceFields v-model:pace="profile.travelPace" v-model:activities="profile.activities" />
      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <button class="btn" :disabled="saving || disabled">
        {{ uploading ? '上传头像中…' : saving ? '保存中…' : submitLabel }}
      </button>
      <button
        v-if="allowSkip"
        class="text-btn"
        type="button"
        :disabled="saving || disabled"
        @click="emit('skip')"
      >
        跳过，暂不修改
      </button>
      <p v-if="saved && !allowSkip" class="notice">
        偏好已保存，新建或加入旅行时会自动带入。已有旅行的偏好保持不变。
      </p>
    </fieldset>
  </form>
</template>

<style scoped>
.profile-fields {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.avatar-file {
  min-width: 0;
}
</style>
