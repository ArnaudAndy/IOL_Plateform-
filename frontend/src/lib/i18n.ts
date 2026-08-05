import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import fr from '@/locales/fr.json'
import en from '@/locales/en.json'
import type {
  ExecutionStatus,
  StandardStatus,
  UserRole,
  WorkflowDirection,
  WorkflowLoadMode,
  WorkflowWriteMode,
} from '@/lib/api/types'

const STORAGE_KEY = 'iol.etl.language'
const SUPPORTED_LANGUAGES = ['fr', 'en'] as const

export type AppLanguage = (typeof SUPPORTED_LANGUAGES)[number]

function normalizeLanguage(value?: string | null): AppLanguage {
  const base = value?.toLowerCase().split('-')[0]
  return base === 'en' ? 'en' : 'fr'
}

function readStoredLanguage(): AppLanguage {
  if (typeof window === 'undefined') return 'fr'
  return normalizeLanguage(window.localStorage.getItem(STORAGE_KEY))
}

i18n.use(initReactI18next).init({
  resources: {
    fr: { translation: fr },
    en: { translation: en },
  },
  lng: readStoredLanguage(),
  fallbackLng: 'fr',
  supportedLngs: SUPPORTED_LANGUAGES,
  interpolation: { escapeValue: false },
  returnNull: false,
})

export function getCurrentLanguage(): AppLanguage {
  return normalizeLanguage(i18n.resolvedLanguage || i18n.language)
}

export function setAppLanguage(language: AppLanguage) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, language)
  }
  return i18n.changeLanguage(language)
}

export function tr(key: string, options?: Record<string, unknown>) {
  return i18n.t(key, options) as string
}

export const TECH_LABELS = {
  get last_watermark() { return tr('technical.last_watermark') },
  get correlationId() { return tr('technical.correlationId') },
  get standard() { return tr('technical.standard') },
  get standardTerm() { return tr('technical.standardTerm') },
  get mediator() { return tr('technical.mediator') },
  get pivot() { return tr('technical.pivot') },
  get dlq() { return tr('technical.dlq') },
  get bronze() { return tr('technical.bronze') },
  get silver() { return tr('technical.silver') },
  get gold() { return tr('technical.gold') },
} as const

export function directionLabel(value?: WorkflowDirection | string | null) {
  if (!value) return tr('common.notSpecified')
  return tr(`enums.direction.${value}`, { defaultValue: value })
}

export function loadModeLabel(value?: WorkflowLoadMode | string | null) {
  if (!value) return tr('common.notSpecified')
  return tr(`enums.loadMode.${value}`, { defaultValue: value })
}

export function writeModeLabel(value?: WorkflowWriteMode | string | null) {
  if (!value) return tr('common.notSpecified')
  return tr(`enums.writeMode.${value}`, { defaultValue: value })
}

export function roleLabel(value?: UserRole | string | null) {
  if (!value) return tr('common.notSpecified')
  return tr(`enums.role.${value}`, { defaultValue: value })
}

export function executionStatusLabel(value?: ExecutionStatus | string | null) {
  if (!value) return tr('common.notSpecified')
  return tr(`enums.executionStatus.${value}`, { defaultValue: value })
}

export function standardStatusLabel(value?: StandardStatus | string | null) {
  if (!value) return tr('common.notSpecified')
  return tr(`enums.standardStatus.${value}`, { defaultValue: value })
}

export function layerStatusLabel(value?: string | null) {
  if (!value) return tr('common.notSpecified')
  return tr(`enums.layerStatus.${value}`, { defaultValue: value })
}

export function dataLayerLabel(value?: string | null) {
  const key = value?.toLowerCase()
  if (key === 'bronze') return TECH_LABELS.bronze
  if (key === 'silver') return TECH_LABELS.silver
  if (key === 'gold') return TECH_LABELS.gold
  if (key === 'dlq') return TECH_LABELS.dlq
  return value || tr('common.notSpecified')
}

export default i18n
