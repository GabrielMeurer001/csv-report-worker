export type ReportError = { code: string; message: string; line?: number }

export type Summary = {
  saleCount: number
  total: string
  totalsByCategory: Record<string, string>
}

export type Report = { id: string } & (
  | { status: 'pending' | 'processing' }
  | { status: 'completed'; result: Summary }
  | { status: 'failed'; error: ReportError }
)

type UploadResponse = { id?: string; error?: ReportError; message?: string }

export async function uploadCsv(file: File) {
  const form = new FormData()
  form.set('file', file)
  const response = await fetch('/reports', { method: 'POST', body: form, signal: AbortSignal.timeout(20_000) })
  const body: UploadResponse = await response.json()
  if (response.ok && body.id) return { id: body.id }
  if (response.status === 503 && body.id) return { id: body.id, message: body.error?.message }
  throw new Error(body.error?.message ?? body.message ?? 'Não foi possível enviar o arquivo.')
}

export async function getReport(id: string, signal: AbortSignal): Promise<Report> {
  const response = await fetch(`/reports/${encodeURIComponent(id)}`, {
    signal: AbortSignal.any([signal, AbortSignal.timeout(10_000)]),
    cache: 'no-store',
  })
  if (response.status === 404) throw new Error('Relatório não encontrado. Confira o ID e tente novamente.')
  if (!response.ok) throw new Error('A consulta falhou. Tente consultar novamente.')
  return response.json()
}

export function formatAmount(value: string) {
  const [whole, decimals] = value.split('.')
  return `${BigInt(whole).toLocaleString('pt-BR')},${decimals}`
}

export function inCents(value: string) {
  return BigInt(value.replace('.', ''))
}
