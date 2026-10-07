import { ApiFailure } from '../api/client'
import { getPreviewState, savePreviewState } from '../api/preview'
import type { StateDocument, StateRequest } from './protocol'

export type PreviewMode = 'CURRENT' | 'HISTORY'
/** Authority comes from the host's selected version, never from an iframe message. */
export function createPreviewContext(
  projectId: string,
  versionId: string,
  mode: PreviewMode,
  initialState: Record<string, unknown>,
) {
  // Props may be Vue proxies; snapshot validated JSON before crossing MessagePort.
  const initialJson = JSON.stringify(initialState)
  return async (request: StateRequest, key: string): Promise<StateDocument> => {
    if (mode === 'HISTORY') {
      if (request.type === 'STATE_SAVE')
        throw new ApiFailure('READ_ONLY_PREVIEW', '历史预览只读，不能保存数据。', 409)
      return { value: JSON.parse(initialJson), revision: 0, stateSchemaVersion: 1 }
    }
    if (request.type === 'STATE_GET') return getPreviewState(projectId, versionId)
    return savePreviewState(
      projectId,
      versionId,
      request.payload.value,
      request.payload.expectedRevision,
      key,
    )
  }
}
