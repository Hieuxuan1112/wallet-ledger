import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { NotificationView, Page } from '../api/types'

export function NotificationsPage() {
  const [items, setItems] = useState<NotificationView[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Page<NotificationView>>('/notifications?size=50').then((p) => setItems(p.content), (e: Error) => setError(e.message))
  }, [])

  return (
    <section>
      {error && <p role="alert" className="text-red-700">{error}</p>}
      {items.length === 0 && !error && <p className="text-sm text-slate-500">No notifications yet. They arrive a few seconds after a transaction, through Kafka.</p>}
      <ul className="divide-y rounded border border-slate-200 bg-white">
        {items.map((n, i) => (
          <li key={i} className="p-3 text-sm">
            <span className="tabular-nums">{n.message}</span>
            <span className="ml-3 text-slate-500">{new Date(n.createdAt).toLocaleString()}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
