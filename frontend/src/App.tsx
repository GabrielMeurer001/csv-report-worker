import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent, DragEvent, FormEvent, ReactNode } from 'react'
import {
  ArrowDownToLine, ArrowRight, ChartNoAxesColumnIncreasing, Check, CircleAlert,
  Copy, FileSpreadsheet, LoaderCircle, Search, UploadCloud, X,
} from 'lucide-react'
import { AnimatePresence, motion } from 'motion/react'
import { formatAmount, getReport, inCents, uploadCsv } from './reports'
import type { Report, Summary } from './reports'

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
const statusLabels = { pending: 'Pendente', processing: 'Processando', completed: 'Concluído', failed: 'Falhou' }
const enter = { initial: { opacity: 0, y: 6 }, animate: { opacity: 1, y: 0 }, exit: { opacity: 0 } }

export function App() {
  const picker = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string>()
  const [lookup, setLookup] = useState('')
  const [lookupError, setLookupError] = useState<string>()
  const [tracking, setTracking] = useState<{ id: string }>()
  const [report, setReport] = useState<Report>()
  const [networkError, setNetworkError] = useState<string>()
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!tracking) return
    const controller = new AbortController()
    let timer: number | undefined

    async function poll(id: string) {
      try {
        const next = await getReport(id, controller.signal)
        if (controller.signal.aborted) return
        setReport(next)
        if (next.status === 'pending' || next.status === 'processing') {
          timer = window.setTimeout(() => void poll(id), 1000)
        }
      } catch (error) {
        if (controller.signal.aborted) return
        setNetworkError(error instanceof Error && error.name === 'Error' ? error.message :
          'A conexão foi interrompida. Tente consultar novamente.')
      }
    }

    void poll(tracking.id)
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [tracking])

  function track(id: string) {
    setReport(undefined)
    setNetworkError(undefined)
    setCopied(false)
    setTracking({ id })
  }

  function chooseFile(selected: File | null) {
    setFile(null)
    setUploadError(undefined)
    if (!selected) return
    if (!selected.name.toLowerCase().endsWith('.csv')) {
      setUploadError('Selecione um arquivo com extensão .csv.')
    } else if (selected.size > 1024 * 1024) {
      setUploadError('O arquivo deve ter no máximo 1 MiB.')
    } else {
      setFile(selected)
    }
  }

  function onFileChange(event: ChangeEvent<HTMLInputElement>) {
    chooseFile(event.currentTarget.files?.[0] ?? null)
    event.currentTarget.value = ''
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    if (uploading) return
    if (event.dataTransfer.files.length !== 1) {
      chooseFile(null)
      setUploadError('Envie um arquivo por vez.')
      return
    }
    chooseFile(event.dataTransfer.files[0])
  }

  async function onUpload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!file || uploading) return
    setUploading(true)
    setUploadError(undefined)
    try {
      const { id, message } = await uploadCsv(file)
      track(id)
      setUploadError(message)
      if (!message) setFile(null)
    } catch (error) {
      setUploadError(error instanceof Error && error.name === 'Error' ? error.message :
        'O envio foi interrompido. Verifique a conexão e tente novamente.')
    } finally {
      setUploading(false)
    }
  }

  function onLookup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (uploading) return
    const id = lookup.trim()
    if (!uuid.test(id)) {
      setLookupError('Informe um ID de relatório válido.')
      return
    }
    setLookupError(undefined)
    track(id)
  }

  const status = report?.status
  const isWaiting = tracking && !networkError && (!status || status === 'pending' || status === 'processing')

  return (
    <>
      <header className="site-header">
        <div className="frame header-inner">
          <span className="brand-mark"><FileSpreadsheet size={20} aria-hidden="true" /></span>
          <span className="brand">CSV Report Worker</span>
        </div>
      </header>

      <main className="frame content">
        <div className="page-heading">
          <h1>Relatórios de vendas</h1>
          <p>Envie um CSV de vendas e acompanhe o processamento até o resumo por categoria.</p>
        </div>

        <div className="workspace">
          <div className="input-column">
            <section className="panel" aria-labelledby="upload-title">
              <PanelTitle icon={<UploadCloud size={19} />} id="upload-title" title="Novo relatório" />
              <form onSubmit={onUpload}>
                <input className="visually-hidden" type="file" accept=".csv,text/csv" ref={picker}
                  aria-label="Arquivo CSV" tabIndex={-1} onChange={onFileChange} disabled={uploading} />
                <div className={`dropzone ${dragging ? 'dragging' : ''}`} onDrop={onDrop}
                  onDragOver={event => { event.preventDefault(); if (!uploading) setDragging(true) }}
                  onDragLeave={event => {
                    if (!(event.relatedTarget instanceof Node) || !event.currentTarget.contains(event.relatedTarget)) setDragging(false)
                  }}>
                  <FileSpreadsheet className="drop-icon" size={25} strokeWidth={1.65} aria-hidden="true" />
                  <div className="drop-content">
                    {file ? <strong title={file.name}>{file.name}</strong> : <strong>Arraste seu CSV aqui</strong>}
                    <p>{file ? `${(file.size / 1024).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} KiB` : 'Arquivos .csv de até 1 MiB'}</p>
                  </div>
                  <div className="file-actions">
                    <button type="button" className="button secondary" onClick={() => picker.current?.click()} disabled={uploading}>
                      {file ? 'Trocar arquivo' : 'Selecionar arquivo'}
                    </button>
                    {file && <button type="button" className="button icon-button" aria-label="Remover arquivo"
                      onClick={() => chooseFile(null)} disabled={uploading}><X size={17} aria-hidden="true" /></button>}
                  </div>
                </div>
                {uploadError && <p className="form-error" role="alert">{uploadError}</p>}
                <div className="upload-actions">
                  <button className="button primary" disabled={!file || uploading}>
                    {uploading ? 'Enviando' : 'Gerar relatório'}
                    {uploading ? <LoaderCircle className="spinner" size={17} aria-hidden="true" /> : <ArrowRight size={17} aria-hidden="true" />}
                  </button>
                  <a className="example-link" href="/examples/sales.csv" download>
                    <ArrowDownToLine size={16} aria-hidden="true" /> CSV de exemplo
                  </a>
                </div>
              </form>
            </section>

            <section className="panel lookup" aria-labelledby="lookup-title">
              <PanelTitle icon={<Search size={19} />} id="lookup-title" title="Consultar por ID" />
              <form onSubmit={onLookup}>
                <div className="lookup-row">
                  <input type="text" aria-label="ID do relatório" aria-invalid={Boolean(lookupError)}
                    aria-describedby={lookupError ? 'lookup-error' : undefined} placeholder="Cole o ID do relatório"
                    value={lookup} onChange={event => { setLookup(event.target.value); setLookupError(undefined) }} />
                  <button className="button secondary" disabled={uploading}>Consultar</button>
                </div>
                {lookupError && <p className="form-error" id="lookup-error" role="alert">{lookupError}</p>}
              </form>
            </section>
          </div>

          <section className="panel result-panel" aria-labelledby="result-title">
            <div className="result-heading">
              <PanelTitle icon={<ChartNoAxesColumnIncreasing size={19} />} id="result-title" title="Resumo do relatório" />
              {status && <span className={`status ${status}`}><span />{statusLabels[status]}</span>}
            </div>
            {tracking && <div className="report-id">
              <span>ID</span> <code>{tracking.id}</code>
              {navigator.clipboard && <button type="button" className="button icon-button" aria-label={copied ? 'ID copiado' : 'Copiar ID'}
                onClick={() => void navigator.clipboard.writeText(tracking.id).then(() => setCopied(true)).catch(() => setCopied(false))}>
                {copied ? <Check size={16} aria-hidden="true" /> : <Copy size={16} aria-hidden="true" />}
              </button>}
            </div>}
            <div className="report-content">
              <AnimatePresence mode="wait" initial={false}>
                {networkError && tracking ? (
                  <motion.div key="network" className="report-message" role="alert" {...enter}>
                    <CircleAlert size={23} aria-hidden="true" />
                    <strong>Consulta interrompida</strong>
                    <p>{networkError}</p>
                    <button className="button secondary" onClick={() => track(tracking.id)}>Consultar novamente</button>
                  </motion.div>
                ) : report?.status === 'failed' ? (
                  <motion.div key="failed" className="report-message failure" role="alert" {...enter}>
                    <CircleAlert size={23} aria-hidden="true" />
                    <strong>{report.error.line ? `Erro na linha ${report.error.line}` : 'Falha no relatório'}</strong>
                    <p>{report.error.message}</p>
                  </motion.div>
                ) : report?.status === 'completed' ? (
                  <motion.div key={`result-${report.id}`} {...enter}><ReportSummary summary={report.result} /></motion.div>
                ) : isWaiting ? (
                  <motion.div key="waiting" className="report-message" role="status" {...enter}>
                    <LoaderCircle className="spinner" size={25} aria-hidden="true" />
                    <strong>{status === 'pending' ? 'Aguardando na fila' : status === 'processing' ? 'Processando vendas' : 'Buscando relatório'}</strong>
                    <p>A página consulta o estado automaticamente.</p>
                  </motion.div>
                ) : (
                  <motion.div key="empty" className="report-message empty" {...enter}>
                    <div className="empty-icon"><ChartNoAxesColumnIncreasing size={29} strokeWidth={1.6} aria-hidden="true" /></div>
                    <strong>Nenhum relatório selecionado</strong>
                    <p>Envie um CSV ou consulte um relatório existente pelo ID.</p>
                  </motion.div>
                )}
              </AnimatePresence>
              {report?.status === 'completed' && <span className="visually-hidden" role="status">Relatório concluído.</span>}
            </div>
          </section>
        </div>
      </main>
    </>
  )
}

function PanelTitle({ icon, id, title }: { icon: ReactNode; id: string; title: string }) {
  return <div className="panel-title"><span className="panel-icon" aria-hidden="true">{icon}</span><h2 id={id}>{title}</h2></div>
}

function ReportSummary({ summary }: { summary: Summary }) {
  const total = inCents(summary.total)
  const categories = Object.entries(summary.totalsByCategory).sort(([first], [second]) => first.localeCompare(second, 'pt-BR'))

  return (
    <>
      <dl className="metrics">
        <div><dt>Quantidade de vendas</dt><dd>{summary.saleCount.toLocaleString('pt-BR')}</dd></div>
        <div><dt>Total de vendas</dt><dd>{formatAmount(summary.total)}</dd></div>
      </dl>
      <h3>Total por categoria</h3>
      <ul className="categories">
        {categories.map(([name, amount]) => {
          const width = total ? Number(inCents(amount) * 1000n / total) / 10 : 0
          return <li key={name}>
            <div className="category-line"><span>{name}</span><strong>{formatAmount(amount)}</strong></div>
            <div className="bar" aria-hidden="true"><span style={{ width: `${width}%` }} /></div>
          </li>
        })}
      </ul>
    </>
  )
}
