export const primaryThemeValues = ['teal', 'blue', 'violet', 'orange', 'rose'] as const
export const appearanceValues = ['light', 'dark'] as const

export type PrimaryTheme = (typeof primaryThemeValues)[number]
export type Appearance = (typeof appearanceValues)[number]

export interface UiPreferences {
  primaryTheme: PrimaryTheme
  appearance: Appearance
}

export interface PreferenceStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface PreferenceTarget {
  dataset: Record<string, string | undefined>
}

export const defaultUiPreferences: Readonly<UiPreferences> = {
  primaryTheme: 'teal',
  appearance: 'light',
}

const preferencesKey = 'guanxian-ui-preferences'
const legacyPrimaryKey = 'guanxian-primary-theme'
const legacyAppearanceKey = 'guanxian-appearance'
const legacyNeutralKey = 'guanxian-neutral-theme'

function isPrimaryTheme(value: unknown): value is PrimaryTheme {
  return typeof value === 'string' && primaryThemeValues.some((item) => item === value)
}

function isAppearance(value: unknown): value is Appearance {
  return typeof value === 'string' && appearanceValues.some((item) => item === value)
}

function parsePreferences(value: string | null): UiPreferences | null {
  if (!value) return null
  try {
    const candidate = JSON.parse(value) as Partial<UiPreferences> | null
    if (!candidate || !isPrimaryTheme(candidate.primaryTheme) || !isAppearance(candidate.appearance)) return null
    return { primaryTheme: candidate.primaryTheme, appearance: candidate.appearance }
  } catch {
    return null
  }
}

export function readUiPreferences(storage: PreferenceStorage): UiPreferences {
  try {
    const stored = parsePreferences(storage.getItem(preferencesKey))
    if (stored) return stored

    const legacyPrimary = storage.getItem(legacyPrimaryKey)
    const legacyAppearance = storage.getItem(legacyAppearanceKey)
    return {
      primaryTheme: isPrimaryTheme(legacyPrimary) ? legacyPrimary : defaultUiPreferences.primaryTheme,
      appearance: isAppearance(legacyAppearance) ? legacyAppearance : defaultUiPreferences.appearance,
    }
  } catch {
    return { ...defaultUiPreferences }
  }
}

export function saveUiPreferences(storage: PreferenceStorage, preferences: UiPreferences): boolean {
  if (!isPrimaryTheme(preferences.primaryTheme) || !isAppearance(preferences.appearance)) return false
  try {
    storage.setItem(preferencesKey, JSON.stringify(preferences))
    storage.removeItem(legacyPrimaryKey)
    storage.removeItem(legacyAppearanceKey)
    storage.removeItem(legacyNeutralKey)
    return true
  } catch {
    return false
  }
}

export function applyUiPreferences(target: PreferenceTarget, preferences: UiPreferences): void {
  target.dataset.primary = preferences.primaryTheme
  target.dataset.appearance = preferences.appearance
  delete target.dataset.neutral
}
