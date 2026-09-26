// Semântica de erros do sync (nunca mensagem genérica perdida).

export function syncErrorMessage(err) {
  const code = err && (err.code || err.data?.code)
  switch (code) {
    case 'WPP_QR_REQUIRED':
      return 'Conecte o WhatsApp antes de sincronizar.'
    case 'WPP_LOGGED_OUT':
      return 'Sessão WhatsApp desconectada. Reconecte antes de sincronizar.'
    case 'OSM_SYNC_ALREADY_RUNNING':
      return 'Já existe uma sincronização em andamento para este target.'
    case 'OSM_REGION_NOT_FOUND':
      return 'Região não encontrada.'
    case 'OSM_TARGET_NOT_FOUND':
      return 'Sincronização não encontrada. Crie uma nova sincronização.'
    case 'OSM_TARGET_CONFIGURATION_REQUIRED':
      return 'Esta sincronização antiga precisa ser configurada novamente.'
    case 'PBF_DOWNLOAD_FAILED':
    case 'PBF_CHECKSUM_FAILED':
      return 'Não foi possível baixar os dados OSM.'
    case 'OSM_SYNC_GITHUB_NOT_CONFIGURED':
      return 'Integração com o worker de sincronização não configurada.'
    default:
      return (err && err.message) || 'Falha na sincronização. Tente novamente.'
  }
}

export function isTerminalRunStatus(status) {
  return status === 'SUCCESS' || status === 'PARTIAL' || status === 'EXHAUSTED' || status === 'FAILED'
}

export function isActiveRunStatus(status) {
  return status === 'QUEUED' || status === 'RUNNING'
}
