import { api, isRecord, protocolFailure } from './client'

export function developmentMode(signal?: AbortSignal) {
  return api.request(
    '/api/development-mode',
    (value) => {
      if (!isRecord(value) || !['ENGINEERING', 'LEGACY'].includes(String(value.mode)))
        return protocolFailure()
      return value.mode as 'ENGINEERING' | 'LEGACY'
    },
    { signal },
  )
}
