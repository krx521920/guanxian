import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { UserRole } from '../../types/domain'
import { associationRoles, enterpriseRoles } from '../../config/roles'
import { useAuth } from '../../services/auth'
import { postLoginDestination } from '../../router/access'
import { safeLocalPath } from '../../services/local-path'

// Keep main's verified entry, binding and safe redirect semantics behind the redesigned card.
export function useLoginFlow() {
  const route = useRoute()
  const router = useRouter()
  const auth = useAuth()
  const selectedRole = ref<UserRole>('ASSOCIATION_ADMIN')
  const loading = ref(false)
  const localError = ref<string | null>(null)
  const entry = computed(() => route.query.entry === 'enterprise' ? 'enterprise'
    : route.query.entry === 'admin' ? 'admin' : null)
  const demoRoles = computed(() => entry.value === 'enterprise' ? enterpriseRoles : [...associationRoles, 'OBSERVER'] as UserRole[])
  watch(entry, (value) => {
    selectedRole.value = value === 'enterprise' ? 'ENTERPRISE_ADMIN' : 'ASSOCIATION_ADMIN'
    localError.value = null
  }, { immediate: true })

  function selectEntry(value: 'enterprise' | 'admin' | null) {
    const redirect = safeLocalPath(route.query.redirect)
    void router.push({ path: '/login', query: { ...(value ? { entry: value } : {}), ...(redirect !== '/' ? { redirect } : {}) } })
  }

  async function login() {
    if (loading.value || !entry.value) return
    loading.value = true
    localError.value = null
    try {
      const redirect = typeof route.query.redirect === 'string' ? route.query.redirect
        : entry.value === 'enterprise' ? '/enterprise/profile' : '/'
      if (auth.isDemoMode) {
        auth.loginDemo(selectedRole.value)
        await router.replace(postLoginDestination(router, auth.user.value!, redirect))
        return
      }
      await auth.login(redirect)
    } catch {
      localError.value = '无法发起身份认证，请联系系统管理员检查 OIDC 配置。'
    } finally {
      loading.value = false
    }
  }
  return { auth, selectedRole, loading, localError, entry, demoRoles, selectEntry, login }
}
