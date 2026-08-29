// Status badge mapping for campaigns and leads.

const CAMPAIGN_LABELS = {
  CREATED: ['Criada', 'badge-gray'],
  DISCOVERING: ['Buscando', 'badge-blue'],
  ANALYZING: ['Analisando', 'badge-blue'],
  GENERATING: ['Gerando', 'badge-blue'],
  COMPLETED: ['Concluida', 'badge-green'],
  FAILED: ['Falha', 'badge-red'],
  PARTIAL: ['Parcial', 'badge-orange']
}

const LEAD_LABELS = {
  NEW: ['Novo', 'badge-gray'],
  ANALYZING: ['Analisando', 'badge-blue'],
  ANALYZED: ['Analisado', 'badge-blue'],
  MESSAGE_READY: ['Mensagem pronta', 'badge-orange'],
  APPROVED: ['Aprovado', 'badge-green'],
  SENT: ['Enviado', 'badge-green'],
  REPLIED: ['Respondeu', 'badge-blue'],
  INTERESTED: ['Interessado', 'badge-green'],
  SCHEDULED: ['Agendado', 'badge-blue'],
  CONVERTED: ['Convertido', 'badge-green'],
  NOT_INTERESTED: ['Nao interessado', 'badge-gray'],
  DO_NOT_CONTACT: ['Nao prospectar', 'badge-red'],
  ERROR: ['Erro', 'badge-red']
}

export function CampaignStatusBadge({ status }) {
  const [label, cls] = CAMPAIGN_LABELS[status] || [status, 'badge-gray']
  return <span className={`badge ${cls}`}>{label}</span>
}

export function LeadStatusBadge({ status }) {
  const [label, cls] = LEAD_LABELS[status] || [status, 'badge-gray']
  return <span className={`badge ${cls}`}>{label}</span>
}
