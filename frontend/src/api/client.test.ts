import { api, ApiError, clearSession, setSessionEndedHandler, storeSession } from './client'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

test('two requests that both find the access token expired share one refresh', async () => {
  storeSession({ accessToken: 'old', refreshToken: 'r1' }, 'alice')
  let refreshes = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/auth/refresh')) {
      refreshes++
      return json(200, { accessToken: 'new', refreshToken: 'r2' })
    }
    const auth = new Headers(init?.headers).get('Authorization')
    return auth === 'Bearer new' ? json(200, { ok: true }) : json(401, { detail: 'expired' })
  }))

  await Promise.all([api('/wallet'), api('/statement')])

  expect(refreshes).toBe(1)
})

test('a failed refresh ends the session', async () => {
  storeSession({ accessToken: 'old', refreshToken: 'reused' }, 'alice')
  const ended = vi.fn()
  setSessionEndedHandler(ended)
  vi.stubGlobal('fetch', vi.fn(async () => json(401, { detail: 'expired' })))

  await expect(api('/wallet')).rejects.toBeInstanceOf(ApiError)
  expect(ended).toHaveBeenCalledOnce()
})

test('the problem detail becomes the error message', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json(409, { title: 'Insufficient funds', detail: 'Not enough' })))

  await expect(api('/wallet/withdrawals', { method: 'POST' })).rejects.toMatchObject({ status: 409, message: 'Not enough' })
})
