import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './styles/main.css'

// 键盘可达性：Esc 关闭当前最上层弹窗（触发遮罩层的 click.self 关闭逻辑）
document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return
  const backdrops = document.querySelectorAll('.modal-backdrop')
  if (!backdrops.length) return
  const topMost = backdrops[backdrops.length - 1]
  if (topMost instanceof HTMLElement) topMost.click()
})

createApp(App).use(router).mount('#app')
