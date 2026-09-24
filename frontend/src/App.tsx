import { useEffect, useState } from 'react'
import { logout, setSessionEndedHandler, storedUsername } from './api/client'
import { AuthPage } from './pages/AuthPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { StatementPage } from './pages/StatementPage'
import { TransferPage } from './pages/TransferPage'
import { WalletPage } from './pages/WalletPage'

const tabs = { Wallet: WalletPage, Transfer: TransferPage, Statement: StatementPage, Notifications: NotificationsPage }
type Tab = keyof typeof tabs

export default function App() {
  const [user, setUser] = useState(storedUsername)
  const [tab, setTab] = useState<Tab>('Wallet')

  useEffect(() => setSessionEndedHandler(() => setUser(null)), [])

  if (!user) return <AuthPage onSignedIn={setUser} />

  const Current = tabs[tab]
  return (
    <div className="mx-auto max-w-4xl p-6">
      <header className="mb-6 flex items-center gap-4">
        <h1 className="font-semibold">Wallet Ledger</h1>
        <nav className="flex gap-2">
          {(Object.keys(tabs) as Tab[]).map((t) => (
            <button key={t} onClick={() => setTab(t)}
              className={t === tab ? 'rounded bg-slate-900 px-3 py-1 text-white' : 'rounded px-3 py-1'}>{t}</button>
          ))}
        </nav>
        <span className="ml-auto text-sm text-slate-500">{user}</span>
        <button className="text-sm underline" onClick={() => { void logout(); setUser(null) }}>Sign out</button>
      </header>
      <Current />
    </div>
  )
}
