// Tiny date formatting helper. No external dependencies.

export function formatDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (isNaN(d.getTime())) return '—'
  return d.toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

export function formatNumber(n) {
  if (n === null || n === undefined) return '0'
  return Number(n).toLocaleString('pt-BR')
}
