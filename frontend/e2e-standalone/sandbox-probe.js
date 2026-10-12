// Reviewed, test-only inline script in the real application document; no credentials are read.
// All URL targets are local and independently intercepted by the test before any network access.
const run = document.createElement('button')
run.textContent = '运行安全探针'
run.addEventListener(
  'click',
  async () => {
    const output = document.createElement('output')
    output.id = 'security-results'
    document.body.append(output)
    const violations = new Set()
    const result = { origin: window.origin }
    const render = () => {
      result.violations = [...violations].sort()
      output.textContent = JSON.stringify(result)
    }
    document.addEventListener('securitypolicyviolation', (event) => {
      violations.add(event.effectiveDirective)
      render()
    })
    const origin = new URL(location.href).origin
    if (!/^http:\/\/127\.0\.0\.1:\d{4,5}$/.test(origin)) throw new Error('Local probe only.')
    try {
      void parent.document.documentElement
      result.parent = 'allowed'
    } catch (error) {
      result.parent = error.name
    }
    try {
      await fetch(origin + '/security-probe/fetch', { credentials: 'omit' })
      result.network = 'allowed'
    } catch (error) {
      result.network = error.name
    }
    const remote = document.createElement('script')
    remote.src = origin + '/security-probe/script.js'
    result.script = await new Promise((resolve) => {
      const deadline = setTimeout(() => resolve('timeout'), 2000)
      const finish = (status) => {
        clearTimeout(deadline)
        resolve(status)
      }
      remote.onload = () => finish('allowed')
      remote.onerror = () => finish('blocked')
      document.body.append(remote)
    })
    remote.remove()
    const blob = URL.createObjectURL(
      new Blob(['postMessage("unexpected")'], { type: 'text/javascript' }),
    )
    result.worker = await new Promise((resolve) => {
      let worker
      const deadline = setTimeout(() => finish('timeout'), 2000)
      const finish = (status) => {
        clearTimeout(deadline)
        worker?.terminate()
        URL.revokeObjectURL(blob)
        resolve(status)
      }
      try {
        worker = new Worker(blob)
        worker.onmessage = () => finish('allowed')
        // Chromium reports CSP worker rejection asynchronously; construction is not execution.
        worker.onerror = (event) => {
          event.preventDefault()
          finish('blocked')
        }
      } catch (error) {
        finish(error.name)
      }
    })
    render()

    const topButton = document.createElement('button')
    topButton.id = 'probe-top'
    topButton.textContent = '测试宿主导航边界'
    topButton.onclick = () => {
      try {
        top.location.href = origin + '/security-probe/top'
        topButton.dataset.result = 'allowed'
      } catch (error) {
        topButton.dataset.result = error.name
      }
    }
    const popupButton = document.createElement('button')
    popupButton.id = 'probe-popup'
    popupButton.textContent = '测试弹窗边界'
    popupButton.onclick = () => {
      const popup = window.open(origin + '/security-probe/popup', '_blank')
      popupButton.dataset.result = popup === null ? 'blocked' : 'allowed'
      popup?.close()
    }
    const selfButton = document.createElement('button')
    selfButton.id = 'probe-self'
    selfButton.textContent = '测试应用自身导航'
    selfButton.onclick = () => {
      location.href = 'about:blank'
    }
    document.body.append(topButton, popupButton, selfButton)
  },
  { once: true },
)
document.body.append(run)
