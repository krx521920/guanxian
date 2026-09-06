import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { UserRole } from '../../types/domain'
import { useAuth } from '../../services/auth'

/**
 * 登录页共享流程：demo 身份选择 与 生产 OIDC 统一认证 两条真实路径。
 * 登录语义与 services/auth.ts 保持一致，不引入任何账号/密码表单。
 */
export function useLoginFlow() {
  const route = useRoute()
  const router = useRouter()
  const auth = useAuth()
  const selectedRole = ref<UserRole>('ASSOCIATION_ADMIN')
  const loading = ref(false)
  const localError = ref<string | null>(null)

  async function login() {
    loading.value = true
    localError.value = null
    try {
      if (auth.isDemoMode) {
        await router.push(auth.loginDemo(selectedRole.value))
        return
      }
      const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
      await auth.login(redirect)
    } catch {
      localError.value = '无法发起身份认证，请联系系统管理员检查 OIDC 配置。'
    } finally {
      loading.value = false
    }
  }

  return { auth, selectedRole, loading, localError, login }
}
