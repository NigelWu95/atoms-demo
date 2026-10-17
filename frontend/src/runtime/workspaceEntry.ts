/** Layout preference only: it carries neither identity nor project data. */
export function workspaceEntry() {
  let entered = false
  const key = 'atoms.workspace.entered.v1'
  return {
    hasEntered() {
      try {
        return entered || localStorage.getItem(key) === '1'
      } catch {
        return entered
      }
    },
    enter() {
      entered = true
      try {
        localStorage.setItem(key, '1')
      } catch {
        /* memory fallback when storage is blocked */
      }
    },
  }
}
