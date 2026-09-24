import type { Session } from './types'

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
  }
}

const REFRESH_KEY = 'wallet.refresh'
const USER_KEY = 'wallet.user'

// In memory only: storage would hand the token to any script that runs on the page.
let accessToken: string | null = null
let refreshInFlight: Promise<void> | null = null
let onSessionEnded: () => void = () => {}

export function setSessionEndedHandler(handler: () => void) {
  onSessionEnded = handler
}

export function storeSession(session: Session, username?: string) {
  accessToken = session.accessToken
  sessionStorage.setItem(REFRESH_KEY, session.refreshToken)
  if (username) sessionStorage.setItem(USER_KEY, username)
}

export function storedUsername(): string | null {
  return sessionStorage.getItem(REFRESH_KEY) ? sessionStorage.getItem(USER_KEY) : null
}

export function clearSession() {
  accessToken = null
  sessionStorage.removeItem(REFRESH_KEY)
  sessionStorage.removeItem(USER_KEY)
}

export async function logout() {
  const refreshToken = sessionStorage.getItem(REFRESH_KEY)
  clearSession()
  if (refreshToken) {
    // Revoke server-side, but never block sign-out on the network.
    await postJson('/auth/logout', { refreshToken }).catch(() => undefined)
  }
}

function postJson(path: string, body: unknown) {
  return fetch(`/api/v1${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function refresh() {
  const refreshToken = sessionStorage.getItem(REFRESH_KEY)
  if (!refreshToken) throw new ApiError(401, 'Not signed in')
  const res = await postJson('/auth/refresh', { refreshToken })
  if (!res.ok) throw new ApiError(res.status, 'Session expired')
  storeSession(await res.json())
}

/**
 * Shared by every caller that hits a 401 at the same moment. The server rotates refresh tokens
 * and treats a reused one as theft, so two parallel refreshes would sign the user out.
 */
function refreshOnce(): Promise<void> {
  refreshInFlight ??= refresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

type Options = { method?: string; body?: string; idempotencyKey?: string }

export async function api<T>(path: string, { method = 'GET', body, idempotencyKey }: Options = {}): Promise<T> {
  const send = () => {
    const headers = new Headers({ 'Content-Type': 'application/json' })
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    if (idempotencyKey) headers.set('Idempotency-Key', idempotencyKey)
    return fetch(`/api/v1${path}`, { method, headers, body })
  }

  let res = await send()
  if (res.status === 401 && !path.startsWith('/auth/')) {
    try {
      await refreshOnce()
    } catch {
      clearSession()
      onSessionEnded()
      throw new ApiError(401, 'Your session has ended. Please sign in again.')
    }
    res = await send()
  }
  if (!res.ok) throw new ApiError(res.status, await problemMessage(res))
  return (res.status === 204 ? undefined : await res.json()) as T
}

async function problemMessage(res: Response): Promise<string> {
  try {
    const problem = await res.json()
    return problem.detail ?? problem.title ?? `HTTP ${res.status}`
  } catch {
    return `HTTP ${res.status}`
  }
}
