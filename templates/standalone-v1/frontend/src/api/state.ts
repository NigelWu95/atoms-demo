import { ApiFailure, freshCsrf, responseFailure } from './client'
import { InvalidResponse, keys, readJson } from './responses'
import { jsonObject, type StateDocument } from '../runtime/protocol'
import type { StateHandler } from '../runtime/bridge'

function stateDocument(value: unknown): StateDocument {
  if (
    !keys(value, ['value', 'revision', 'stateSchemaVersion']) ||
    !jsonObject(value.value) ||
    typeof value.revision !== 'number' ||
    !Number.isSafeInteger(value.revision) ||
    value.revision < 0 ||
    value.stateSchemaVersion !== 1
  )
    throw new InvalidResponse()
  return { value: value.value, revision: value.revision, stateSchemaVersion: 1 }
}

/** One attempt per SDK invocation. The SDK retains the original snapshot and request ID on unknown outcomes. */
export function createStateHandler(loadedRuntimeId: string, lifetime: AbortSignal): StateHandler {
  return async (request, key) => {
    const controller = new AbortController()
    const cancel = () => controller.abort(lifetime.reason)
    lifetime.addEventListener('abort', cancel, { once: true })
    if (lifetime.aborted) cancel()
    const timer = setTimeout(
      () => controller.abort(new DOMException('Deadline', 'TimeoutError')),
      8000,
    )
    let putSent = false
    try {
      controller.signal.throwIfAborted()
      const writing = request.type === 'STATE_SAVE'
      const expectedAcknowledgement = writing ? request.payload.expectedRevision + 1 : null
      // Snapshot synchronously, before awaiting CSRF. Never read mutable application input again.
      const body = writing
        ? JSON.stringify({ loadedRuntimeId, ...request.payload, stateSchemaVersion: 1 })
        : undefined
      const token = writing ? await freshCsrf(controller.signal) : undefined
      controller.signal.throwIfAborted()
      putSent = writing
      const response = await fetch('/api/state', {
        method: writing ? 'PUT' : 'GET',
        credentials: 'same-origin',
        mode: 'same-origin',
        redirect: 'error',
        cache: 'no-store',
        signal: controller.signal,
        headers: {
          Accept: 'application/json',
          ...(token
            ? { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token, 'Idempotency-Key': key }
            : {}),
        },
        ...(body ? { body } : {}),
      })
      const result = await readJson(response, response.status === 200 ? 272 * 1024 : 16 * 1024)
      if (response.status >= 400) throw responseFailure(result, response.status)
      if (response.status !== 200) throw new InvalidResponse()
      const document = stateDocument(result)
      if (expectedAcknowledgement !== null && document.revision !== expectedAcknowledgement)
        throw new InvalidResponse()
      return document
    } catch (failure) {
      const error =
        failure instanceof ApiFailure
          ? failure
          : new ApiFailure(
              controller.signal.aborted
                ? controller.signal.reason?.name === 'TimeoutError'
                  ? 'TIMEOUT'
                  : 'CANCELLED'
                : failure instanceof InvalidResponse
                  ? 'INVALID_RESPONSE'
                  : 'NETWORK_ERROR',
            )
      const confirmedRejection =
        error.status >= 400 && error.status < 500 && error.code !== 'INVALID_RESPONSE'
      throw new ApiFailure(
        error.code,
        error.status,
        error.requestId,
        putSent && !confirmedRejection,
      )
    } finally {
      clearTimeout(timer)
      lifetime.removeEventListener('abort', cancel)
    }
  }
}
