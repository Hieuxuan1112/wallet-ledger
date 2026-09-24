import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { WalletView } from '../api/types'
import { MoneyForm } from '../components/MoneyForm'

export function WalletPage() {
  const [wallet, setWallet] = useState<WalletView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<WalletView>('/wallet').then(setWallet, (e: Error) => setError(e.message))
  }, [])

  const onDone = (tx: { balanceAfter: string }) => setWallet((w) => (w ? { ...w, balance: tx.balanceAfter } : w))

  return (
    <section className="space-y-4">
      {error && <p role="alert" className="text-red-700">{error}</p>}
      {wallet && (
        <div className="rounded border border-slate-200 bg-white p-4">
          <p className="text-sm text-slate-500">Account #{wallet.accountId}</p>
          <p className="text-3xl font-semibold tabular-nums">{wallet.balance}</p>
        </div>
      )}
      <div className="grid gap-4 md:grid-cols-2">
        <MoneyForm label="Deposit" path="/wallet/deposits" onDone={onDone} />
        <MoneyForm label="Withdraw" path="/wallet/withdrawals" onDone={onDone} />
      </div>
    </section>
  )
}
