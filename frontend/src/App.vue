<script setup>
import AppLayout from './layouts/AppLayout.vue'
import { useRoute } from 'vue-router'
const route = useRoute()
function enterPage() {
  if (route.hash === '#create')
    document.getElementById('create')?.scrollIntoView({ block: 'start' })
}
</script>
<template>
  <AppLayout>
    <RouterView v-slot="{ Component, route }">
      <Transition
        name="page"
        mode="out-in"
        @before-leave="(element) => (element.inert = true)"
        @after-enter="enterPage"
      >
        <div :key="route.path" class="page-scene"><component :is="Component" /></div>
      </Transition>
    </RouterView>
  </AppLayout>
</template>
