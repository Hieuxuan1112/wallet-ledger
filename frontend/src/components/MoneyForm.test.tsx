import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MoneyForm } from './MoneyForm'

afterEach(() => vi.unstubAllGlobals())

test('a retry after a failure resends the same Idempotency-Key', async () => {
  const keys: (string | null)[] = []
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
    keys.push(new Headers(init?.headers).get('Idempotency-Key'))
    return keys.length === 1
      ? new Response(JSON.stringify({ detail: 'Gateway timeout' }), { status: 504 })
      : new Response(JSON.stringify({ transactionId: 't1', type: 'DEPOSIT', amount: '5.0000', balanceAfter: '5.0000' }), { status: 201 })
  }))
  const onDone = vi.fn()
  render(<MoneyForm label="Deposit" path="/wallet/deposits" onDone={onDone} />)

  fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '5' } })
  fireEvent.click(screen.getByRole('button', { name: 'Deposit' }))
  await screen.findByText('Gateway timeout')
  fireEvent.click(screen.getByRole('button', { name: 'Deposit' }))

  await waitFor(() => expect(onDone).toHaveBeenCalledOnce())
  expect(keys).toHaveLength(2)
  expect(keys[0]).not.toBeNull()
  expect(keys[1]).toBe(keys[0])
})
