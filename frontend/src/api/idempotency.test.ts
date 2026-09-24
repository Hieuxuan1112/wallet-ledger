import { createKeyTracker, newKey } from './idempotency'

test('a retry of the same submission reuses its key', () => {
  const keys = createKeyTracker()
  const first = keys.keyFor('{"amount":"5"}')
  expect(keys.keyFor('{"amount":"5"}')).toBe(first)
})

test('an edited submission gets a new key', () => {
  const keys = createKeyTracker()
  const first = keys.keyFor('{"amount":"5"}')
  expect(keys.keyFor('{"amount":"6"}')).not.toBe(first)
})

test('success clears the key, so the next identical submission is a new operation', () => {
  const keys = createKeyTracker()
  const first = keys.keyFor('{"amount":"5"}')
  keys.succeeded()
  expect(keys.keyFor('{"amount":"5"}')).not.toBe(first)
})

test('keys are version 4 UUIDs', () => {
  expect(newKey()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})
