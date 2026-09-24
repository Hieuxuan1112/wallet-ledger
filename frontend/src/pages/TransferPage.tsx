import { useState } from 'react'
import type { TransactionView } from '../api/types'
import { MoneyForm } from '../components/MoneyForm'

export function TransferPage() {
  const [last, setLast] = useState<TransactionView | null>(null)
  return (
    <section className="max-w-md space-y-4">
      <MoneyForm label="Transfer" path="/transfers" withRecipient onDone={setLast} />
      {last && (
        <p className="text-sm">
          Sent {last.amount}. Balance now <span className="tabular-nums">{last.balanceAfter}</span>.
        </p>
      )}
    </section>
  )
}
