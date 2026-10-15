<script setup>
defineProps({ tripId: String })
</script>
<template>
  <nav class="workspace-nav" aria-label="本次旅行导航">
    <RouterLink :to="`/trips/${tripId}`" exact-active-class="selected"><span>01</span> 小队与讨论</RouterLink>
    <RouterLink :to="`/trips/${tripId}/itinerary`" exact-active-class="selected"><span>02</span> 行程与安排</RouterLink>
    <a href="#trip-chat" class="workspace-chat-link">聊聊想法 <span>↙</span></a>
  </nav>
</template>
