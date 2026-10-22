<script setup>
import { money, paceNames, parseActivities, sameId } from '../utils/format.js'
import { useAuthStore } from '../stores/auth.js'
import UserAvatar from './UserAvatar.vue'
defineProps({ members: { type: Array, default: () => [] }, userId: [String, Number] })
const auth = useAuthStore()
function memberName(member) {
  // 本人保存资料后立即显示新昵称，其他成员使用后端从 user 表补充的昵称。
  return (
    (sameId(member.userId, auth.user?.id) ? auth.user?.nickname : '') ||
    member.nickname ||
    '未知成员'
  )
}
</script>
<template>
  <div class="member-list">
    <article v-for="member in members" :key="member.userId" class="member-card">
      <UserAvatar :src="member.avatarUrl" :name="memberName(member)" :size="40" />
      <div class="grow">
        <strong
          >{{ memberName(member)
          }}{{ sameId(member.userId, userId) ? '（我）' : '' }}</strong
        ><span v-if="member.role === 'owner'" class="owner-label">发起人</span>
        <p>{{ paceNames[member.travelPace] || '节奏未设置' }}</p>
        <p>偏好：{{ parseActivities(member.favoriteActivities).join(' / ') || '暂未设置' }}</p>
      </div>
      <span class="member-badge">{{ money(member.budgetLimit) }}</span>
    </article>
  </div>
</template>
