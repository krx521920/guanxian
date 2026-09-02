export function displayEffectiveDate(effectiveDate: string | null): string {
  return effectiveDate?.trim() || '暂未公布'
}

export function safeExternalUrl(value: string | null | undefined): string | null {
  if (!value?.trim()) return null
  try {
    const url = new URL(value.trim())
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password) return null
    return url.href
  } catch {
    return null
  }
}
