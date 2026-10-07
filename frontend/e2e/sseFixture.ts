import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { test as base } from '@playwright/test'

// A real HTTP stream is necessary to verify native EventSource reconnect headers.
// No external traffic, credentials inspection, or model request is involved.
export const test = base.extend<{
  sseServer: {
    url: string
    handle: (listener: (request: IncomingMessage, response: ServerResponse) => void) => void
  }
}>({
  sseServer: async ({}, use) => {
    const server = createServer()
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    const address = server.address()
    if (!address || typeof address === 'string') throw new Error('Missing local SSE address')
    try {
      await use({
        url: `http://127.0.0.1:${address.port}`,
        handle: (listener) => server.on('request', listener),
      })
    } finally {
      server.closeAllConnections()
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      )
    }
  },
})
