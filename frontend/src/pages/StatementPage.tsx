import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { useIdempotencyKey } from '../api/idempotency'
import type { Page, StatementEntry, TransactionView } from '../api/types'
import { inputClass } from '../components/MoneyForm'

/** A yyyy-mm-dd picked in the user's time zone, as the instant that local day starts. */
function startOfLocalDay(date: string, plusDays = 0): string {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(y, m - 1, d + plusDays).toISOString()
}

export function StatementPage() {
  const refundKeys = useIdempotencyKey()
  const [page, setPage] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [type, setType] = useState('')
  const [data, setData] = useState<Page<StatementEntry> | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = useCallback(() => {
    const query = new URLSearchParams({ page: String(page), size: '10' })
    if (from) query.set('from', startOfLocalDay(from))
    if (to) query.set('to', startOfLocalDay(to, 1)) // the server's "to" is exclusive
    if (type) query.set('type', type)
    api<Page<StatementEntry>>(`/statement?${query}`).then(setData, (e: Error) => setMessage(e.message))
  }, [page, from, to, type])

  useEffect(load, [load])

  async function refund(transactionId: string) {
    setMessage(null)
    try {
      const tx = await api<TransactionView>(`/transactions/${transactionId}/refund`, {
        method: 'POST',
        idempotencyKey: refundKeys.keyFor(transactionId),
      })
      refundKeys.succeeded()
      setMessage(`Refunded. Balance now ${tx.balanceAfter}.`)
      load()
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Refund failed')
    }
  }

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm">From<input type="date" className={inputClass} value={from} onChange={(e) => { setPage(0); setFrom(e.target.value) }} /></label>
        <label className="text-sm">To<input type="date" className={inputClass} value={to} onChange={(e) => { setPage(0); setTo(e.target.value) }} /></label>
        <label className="text-sm">Type
          <select className={inputClass} value={type} onChange={(e) => { setPage(0); setType(e.target.value) }}>
            <option value="">All</option>
            <option>DEPOSIT</option><option>WITHDRAWAL</option><option>TRANSFER</option><option>REVERSAL</option>
          </select>
        </label>
      </div>
      {message && <p role="status" className="text-sm">{message}</p>}
      <table className="w-full border-collapse bg-white text-sm">
        <thead><tr className="border-b text-left"><th className="p-2">When</th><th>Type</th><th>Description</th><th className="text-right">Amount</th><th /></tr></thead>
        <tbody>
          {data?.content.map((row) => (
            <tr key={`${row.transactionId}-${row.amount}`} className="border-b">
              <td className="p-2">{new Date(row.createdAt).toLocaleString()}</td>
              <td>{row.type}</td>
              <td>{row.description}</td>
              <td className="text-right tabular-nums">{row.amount}</td>
              <td className="text-right">
                {/* The server decides who may refund (initiator only); the UI does not guess. */}
                {row.type !== 'REVERSAL' && (
                  <button className="px-2 underline" onClick={() => refund(row.transactionId)}>Refund</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {data && data.totalPages > 1 && (
        <div className="flex items-center gap-3">
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span className="text-sm">Page {data.number + 1} of {data.totalPages}</span>
          <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </section>
  )
}
