import type { VersionSummary } from './artifacts'
import { api, protocolFailure } from './client'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export function engineeringFilename(version: VersionSummary): string {
  if (
    !uuid.test(version.projectId) ||
    !uuid.test(version.id) ||
    !Number.isSafeInteger(version.versionNo) ||
    version.versionNo < 1
  )
    return protocolFailure()
  return `atoms-${version.projectId}-v${version.versionNo}-engineering.zip`
}

export async function downloadEngineering(
  version: VersionSummary,
  signal: AbortSignal,
): Promise<Blob> {
  engineeringFilename(version)
  const { bytes, type } = await api.binary(
    `/api/projects/${version.projectId}/versions/${version.id}/engineering.zip`,
    signal,
    9 * 1024 * 1024,
  )
  signal.throwIfAborted()
  if (
    type?.split(';')[0]?.trim().toLowerCase() !== 'application/zip' ||
    bytes[0] !== 80 ||
    bytes[1] !== 75 ||
    bytes[2] !== 3 ||
    bytes[3] !== 4
  )
    return protocolFailure()
  return new Blob([bytes], { type: 'application/zip' })
}
