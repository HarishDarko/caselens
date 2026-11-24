import { useEffect, useRef, type ReactNode } from 'react'
import type { RuntimeSummary } from './api'
import { Byline, CloseIcon, SOURCE_URL, Wordmark } from './ui'

/**
 * A modal built on the native dialog element: focus moves into it, Escape closes it, and focus returns to the control
 * that opened it. A browser without showModal (or jsdom) gets an open, non-modal dialog.
 */
export function Modal({ id, label, labelledBy, className, onClose, children }: { id: string; label?: string; labelledBy?: string; className?: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    if (typeof dialog.showModal === 'function') { if (!dialog.open) dialog.showModal() } else dialog.setAttribute('open', '')
    return () => { if (typeof dialog.close === 'function' && dialog.open) dialog.close(); opener?.focus() }
  }, [])
  return (
    <dialog ref={ref} id={id} className={className ? `panel-dialog ${className}` : 'panel-dialog'} aria-label={label} aria-labelledby={labelledBy}
      onCancel={(event) => { event.preventDefault(); onClose() }}
      onClick={(event) => {
        // A click on the backdrop lands on the dialog element itself, outside its box; a click on its padding does not close it.
        const box = ref.current?.getBoundingClientRect()
        if (event.target !== ref.current || !box || box.width === 0) return
        if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) onClose()
      }}>
      {children}
    </dialog>
  )
}

const RUNTIME_LABELS = {
  environment: { LOCAL_REVIEW: 'a local review stack', AWS_DEMO: 'AWS' },
  database: { POSTGRESQL: 'PostgreSQL', NEON_POSTGRESQL: 'Neon Postgres' },
  queue: { LOCALSTACK_SQS: 'LocalStack SQS', AMAZON_SQS: 'Amazon SQS' },
  aiProvider: { GROQ: 'Groq', GEMINI: 'Gemini', DETERMINISTIC: 'the deterministic test provider' },
} as const

/** What happens to one ticket, from the click to the call a person accepts. The tech stack lives here, not in the header. */
export function HowItsBuilt({ runtime, onClose }: { runtime: RuntimeSummary | null; onClose: () => void }) {
  return (
    <Modal id="built" labelledBy="built-title" onClose={onClose}>
      <div className="dialog-head">
        <h2 id="built-title">How it's built</h2>
        <button type="button" className="icon-button" aria-label="Close" onClick={onClose}><CloseIcon /></button>
      </div>
      <p className="built-lede">What happens to one ticket when you open it, from the click to the call you accept.</p>
      <ol className="flow">
        <li>
          <span className="node" aria-hidden="true">1</span>
          <div>
            <h3 className="step-h">The ticket and its job are saved together</h3>
            <p className="step-p">The API writes the triage job in the same Postgres transaction as the ticket (a transactional outbox), so one is never saved without the other.</p>
            <div className="chips"><span className="chip">Java 21</span><span className="chip">Spring Boot</span><span className="chip">AWS Lambda with SnapStart</span><span className="chip">Neon Postgres</span></div>
          </div>
        </li>
        <li>
          <span className="node" aria-hidden="true">2</span>
          <div>
            <h3 className="step-h">A relay puts the job on the queue</h3>
            <p className="step-p">Failed attempts retry on their own. A job that keeps failing waits in a dead-letter queue until someone replays it.</p>
            <div className="chips"><span className="chip">Amazon SQS</span><span className="chip">Dead-letter queue</span><span className="chip">Replay from Quality and operations</span></div>
          </div>
        </li>
        <li>
          <span className="node" aria-hidden="true">3</span>
          <div>
            <h3 className="step-h">The worker asks the model</h3>
            <p className="step-p">Emails, phone and card numbers and payment IDs are redacted first. The model must answer in a fixed JSON shape: category, urgency, policies, evidence quotes and next steps.</p>
            <div className="chips"><span className="chip">Groq</span><span className="chip">openai/gpt-oss-20b</span><span className="chip">Structured output</span></div>
          </div>
        </li>
        <li className="is-check">
          <span className="node" aria-hidden="true">4</span>
          <div>
            <h3 className="step-h">Every answer is checked before you see it</h3>
            <div className="checks-row">
              <span>Each quote is found word for word in the ticket</span>
              <span>The category is in this industry's pack</span>
              <span>Every policy it cites is in the pack's catalog</span>
            </div>
            <div className="outcomes">
              <div className="outcome ok"><b>AI verified</b>All checks pass. The model's answer is shown with its quotes highlighted.</div>
              <div className="outcome fb"><b>Rules fallback</b>The model failed or its answer failed a check. The pack's keyword rules decide instead, and the ticket says so.</div>
            </div>
          </div>
        </li>
        <li>
          <span className="node" aria-hidden="true">5</span>
          <div>
            <h3 className="step-h">Code scores priority, a person makes the call</h3>
            <p className="step-p">The model never picks the number: fixed rules add it up from the AI's urgency, its response-time risk and words in the ticket. The result is stored and never overwritten; each Accept or override is added beside it.</p>
          </div>
        </li>
      </ol>
      <div className="guards">
        <div className="guard"><b>20 a day</b><span>live AI runs per network, 80 in total, so a busy day stays inside the free AI quota</span></div>
        <div className="guard"><b>24 hours</b><span>then the demo workspace and everything in it is deleted</span></div>
        <div className="guard"><b>0 real customers</b><span>every ticket is synthetic, written for its industry</span></div>
      </div>
      <p className="step-p">Anonymous, cookieless analytics.</p>
      <div className="built-foot">
        <span>{runtime
          ? `This workspace runs on ${RUNTIME_LABELS.environment[runtime.environment]}: ${RUNTIME_LABELS.database[runtime.database]}, ${RUNTIME_LABELS.queue[runtime.queue]} and ${RUNTIME_LABELS.aiProvider[runtime.aiProvider]}.`
          : 'Recorded results come from real runs of this pipeline; the rest run live when you open them.'}</span>
        <Byline> · <a href={SOURCE_URL} target="_blank" rel="noopener">Read the code on GitHub</a></Byline>
      </div>
    </Modal>
  )
}

export type NavView = 'inbox' | 'quality'

/** The phone and tablet menu: the screens, How it's built, Close demo and the byline. */
export function PhoneMenu({ industryName, view, onNavigate, onShowBuilt, onCloseDemo, onClose }: { industryName: string; view: NavView; onNavigate: (view: NavView) => void; onShowBuilt: () => void; onCloseDemo: () => void; onClose: () => void }) {
  const item = (target: NavView, label: string) => (
    <button type="button" className="nav-item" aria-current={view === target ? 'page' : undefined} onClick={() => { onClose(); onNavigate(target) }}><span>{label}</span></button>
  )
  return (
    <Modal id="menu" label="Menu" className="menu-dialog" onClose={onClose}>
      <div className="dialog-head">
        <Wordmark />
        <button type="button" className="icon-button" aria-label="Close menu" onClick={onClose}><CloseIcon /></button>
      </div>
      <p className="menu-scope">Demo workspace, {industryName}</p>
      <nav className="menu-nav" aria-label="Screens">
        {item('inbox', 'Inbox')}
        {item('quality', 'Quality and operations')}
        <button type="button" className="nav-item" onClick={onShowBuilt}><span>How it's built</span></button>
        <button type="button" className="nav-item" onClick={() => { onClose(); onCloseDemo() }}><span>Close demo</span></button>
      </nav>
      <Byline />
    </Modal>
  )
}
