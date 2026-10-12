import { api, protocolFailure } from './client'
export type BuildArchiveTarget = { id: string; projectId: string }
export function buildArchiveFilename(target: BuildArchiveTarget) {
  const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
  if (!uuid.test(target.id) || !uuid.test(target.projectId)) return protocolFailure()
  return `atoms-build-${target.id}-engineering.zip`
}
export async function downloadBuildArchive(
  target: BuildArchiveTarget,
  signal: AbortSignal,
): Promise<Blob> {
  buildArchiveFilename(target)
  const { bytes, type } = await api.binary(
    `/api/projects/${target.projectId}/builds/${target.id}/engineering.zip`,
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
