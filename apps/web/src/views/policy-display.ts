export function displayEffectiveDate(effectiveDate: string | null): string {
  return effectiveDate?.trim() || '暂未公布'
}
