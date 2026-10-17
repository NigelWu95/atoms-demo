const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
const projectPath = new RegExp(`^/projects/(${uuid})$`, 'i')
const previewPath = new RegExp(`^/preview/(${uuid})/${uuid}$`, 'i')

/** Untrusted query input: only known private UI paths, never a URL or account loop. */
export function accountReturnTarget(
  value: unknown,
): { path: string; projectId: string | null } | null {
  if (typeof value !== 'string') return null
  if (['/', '/create', '/settings/account', '/projects', '/workbench'].includes(value))
    return { path: value, projectId: null }
  const match = projectPath.exec(value) ?? previewPath.exec(value)
  return match && match[0] === value ? { path: value, projectId: match[1]! } : null
}
