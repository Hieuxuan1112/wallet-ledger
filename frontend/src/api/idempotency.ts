import { useState } from 'react'

/** A v4 UUID from getRandomValues: crypto.randomUUID() is missing outside secure (HTTPS) contexts. */
export function newKey(): string {
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/**
 * One key per submission, where "the same submission" means the same payload. A retry after a
 * timeout resends the key, so the server replays instead of paying twice; an edited form gets a
 * new key, because the server rejects a key reused for a different body (422).
 */
export function createKeyTracker() {
  let last: { payload: string; key: string } | null = null
  return {
    keyFor(payload: string): string {
      if (last?.payload !== payload) last = { payload, key: newKey() }
      return last.key
    },
    succeeded() {
      last = null
    },
  }
}

export function useIdempotencyKey() {
  return useState(createKeyTracker)[0]
}
