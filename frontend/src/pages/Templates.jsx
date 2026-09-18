import { useState, useEffect, useRef } from 'react'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { IconSave } from '../components/Icons.jsx'

const ALLOWED_VARS = ['{nome}', '{cidade}', '{nicho}', '{instagram}']

export function Templates() {
  const [template, setTemplate] = useState(null)
  const [text, setText] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const { push } = useToast()
  const textareaRef = useRef(null)

  useEffect(() => {
    load()
  }, [])

  function load() {
    setLoading(true)
    api.get('/api/message-templates/default')
      .then(d => {
        setTemplate(d)
        setText(d?.templateText || '')
      })
      .catch(err => {
        if (err.status !== 404) {
          push('Erro ao carregar template: ' + err.message, 'error')
        }
      })
      .finally(() => setLoading(false))
  }

  async function save() {
    setSaving(true)
    try {
      if (template?.id) {
        const res = await api.put('/api/message-templates/default', {
          ...template,
          templateText: text,
          isDefault: true
        })
        setTemplate(res)
      } else {
        const res = await api.post('/api/message-templates', {
          name: 'Template Padrao',
          templateText: text,
          isDefault: true
        })
        setTemplate(res)
      }
      push('Template salvo com sucesso.', 'success')
    } catch (err) {
      push('Erro ao salvar template: ' + err.message, 'error')
    } finally {
      setSaving(false)
    }
  }

  function insertVar(v) {
    if (!textareaRef.current) return
    const el = textareaRef.current
    const start = el.selectionStart
    const end = el.selectionEnd
    const newText = text.substring(0, start) + v + text.substring(end)
    setText(newText)
    setTimeout(() => {
      el.focus()
      el.setSelectionRange(start + v.length, start + v.length)
    }, 0)
  }

  const invalidVars = []
  const matches = text.match(/\{[^}]+\}/g) || []
  matches.forEach(m => {
    if (!ALLOWED_VARS.includes(m)) {
      invalidVars.push(m)
    }
  })

  if (loading) return <div className="loading">Carregando template...</div>

  return (
    <div>
      <div className="flex-between">
        <div>
          <h1 className="page-title">Templates de Mensagem</h1>
          <p className="page-sub">Edite o template padrao de prospeccao.</p>
        </div>
        <button className="btn btn-primary" onClick={save} disabled={saving || invalidVars.length > 0}>
          <IconSave width={15} height={15} />
          {saving ? 'Salvando...' : 'Salvar'}
        </button>
      </div>

      <div className="card" style={{ maxWidth: 800 }}>
        <div style={{ marginBottom: 12 }}>
          <label>Variaveis permitidas (clique para inserir no cursor):</label>
          <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
            {ALLOWED_VARS.map(v => (
              <button key={v} className="btn btn-sm" onClick={() => insertVar(v)}>{v}</button>
            ))}
          </div>
        </div>

        <textarea
          ref={textareaRef}
          value={text}
          onChange={e => setText(e.target.value)}
          rows={10}
          style={{ width: '100%', fontFamily: 'monospace', padding: 12, border: invalidVars.length > 0 ? '1px solid red' : '1px solid #ccc' }}
          placeholder="Ola {nome}, tudo bem? Vi que voce tem um negocio de {nicho} em {cidade}..."
        />

        {invalidVars.length > 0 && (
          <div className="error-state" style={{ marginTop: 12, textAlign: 'left' }}>
            <strong>Variaveis invalidas encontradas:</strong> {invalidVars.join(', ')}
            <p className="muted" style={{ marginTop: 4 }}>
              Apenas as variaveis permitidas acima sao suportadas.
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
