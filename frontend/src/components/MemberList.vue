<script setup>
import { money, paceNames, parseActivities, sameId } from '../utils/format.js'
defineProps({ members: { type: Array, default: () => [] }, userId: [String, Number] })
</script>
<template>
  <div class="member-list">
    <article v-for="member in members" :key="member.userId" class="member-card">
      <div class="member-icon" aria-hidden="true">{{ member.role === 'owner' ? '🌿' : '🎒' }}</div>
      <div class="grow">
        <strong
          >{{ member.nickname || '旅行伙伴'
          }}{{ sameId(member.userId, userId) ? '（我）' : '' }}</strong
        ><span v-if="member.role === 'owner'" class="owner-label">发起人</span>
        <p>{{ paceNames[member.travelPace] || '节奏未设置' }}</p>
        <p>偏好：{{ parseActivities(member.favoriteActivities).join(' / ') || '暂未设置' }}</p>
      </div>
      <span class="member-badge">{{ money(member.budgetLimit) }}</span>
    </article>
  </div>
</template>
