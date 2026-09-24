import { useState } from 'react'
import type { FormEvent } from 'react'
import { api } from '../api/client'
import { useIdempotencyKey } from '../api/idempotency'
import type { TransactionView } from '../api/types'

type Props = {
  label: string
  path: string
  withRecipient?: boolean
  onDone: (tx: TransactionView) => void
}

export const inputClass = 'mt-1 block w-full rounded border border-slate-300 px-3 py-2'
export const buttonClass = 'rounded bg-slate-900 px-4 py-2 text-white disabled:opacity-50'

export function MoneyForm({ label, path, withRecipient = false, onDone }: Props) {
  const keys = useIdempotencyKey()
  const [toUsername, setToUsername] = useState('')
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    // The amount goes as the string the user typed; the server rejects more than 4 decimals.
    const body = JSON.stringify(withRecipient ? { toUsername, amount, description } : { amount, description })
    try {
      const tx = await api<TransactionView>(path, { method: 'POST', body, idempotencyKey: keys.keyFor(body) })
      keys.succeeded()
      setToUsername('')
      setAmount('')
      setDescription('')
      onDone(tx)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-3 rounded border border-slate-200 bg-white p-4">
      <h2 className="font-semibold">{label}</h2>
      {withRecipient && (
        <label className="block text-sm">
          Recipient username
          <input className={inputClass} value={toUsername} onChange={(e) => setToUsername(e.target.value)} required />
        </label>
      )}
      <label className="block text-sm">
        Amount
        <input className={inputClass} inputMode="decimal" pattern="\d+(\.\d{1,4})?" value={amount}
          onChange={(e) => setAmount(e.target.value)} required />
      </label>
      <label className="block text-sm">
        Description
        <input className={inputClass} maxLength={255} value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      <button type="submit" className={buttonClass} disabled={busy}>{label}</button>
    </form>
  )
}
