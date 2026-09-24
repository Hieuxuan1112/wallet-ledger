import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, storeSession } from '../api/client'
import type { Session } from '../api/types'
import { buttonClass, inputClass } from '../components/MoneyForm'

export function AuthPage({ onSignedIn }: { onSignedIn: (username: string) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const body = JSON.stringify({ username, password })
    try {
      if (mode === 'register') await api('/auth/register', { method: 'POST', body })
      storeSession(await api<Session>('/auth/login', { method: 'POST', body }), username)
      onSignedIn(username)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    }
  }

  return (
    <main className="mx-auto mt-16 max-w-sm">
      <form onSubmit={submit} className="space-y-3 rounded border border-slate-200 bg-white p-6">
        <h1 className="text-xl font-semibold">{mode === 'login' ? 'Sign in' : 'Create account'}</h1>
        <label className="block text-sm">
          Username
          <input className={inputClass} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
        </label>
        <label className="block text-sm">
          Password
          <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={mode === 'register' ? 8 : undefined} required />
        </label>
        {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
        <button type="submit" className={buttonClass}>{mode === 'login' ? 'Sign in' : 'Register'}</button>
        <button type="button" className="ml-3 text-sm underline" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
          {mode === 'login' ? 'Need an account?' : 'Have an account?'}
        </button>
      </form>
    </main>
  )
}
