/** Amounts are strings on the wire and stay strings here: a JS number would round NUMERIC(19,4). */
export type Session = { accessToken: string; refreshToken: string }
export type WalletView = { accountId: number; balance: string }
export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER' | 'REVERSAL'
export type TransactionView = {
  transactionId: string
  type: TransactionType
  amount: string
  balanceAfter: string
}
export type StatementEntry = {
  transactionId: string
  type: TransactionType
  amount: string
  description: string | null
  createdAt: string
}
export type NotificationView = { type: string; message: string; createdAt: string }
export type Page<T> = { content: T[]; totalPages: number; number: number }
