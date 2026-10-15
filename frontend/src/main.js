import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router/index.js'
import { useAuthStore } from './stores/auth.js'
import { setUnauthorizedHandler } from './api/http.js'
import './styles/prototype.css'
import './styles/app.css'
import './styles/design.css'

const app = createApp(App)
app.use(createPinia())
const auth = useAuthStore()
auth.connectTabs()
setUnauthorizedHandler(() => {
  auth.clear()
  if (router.currentRoute.value.meta.auth)
    router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})
await auth.restore()
app.use(router)
app.mount('#app')
