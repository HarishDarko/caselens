import type { Industry } from './api'
import { placeEvidence } from './evidence'
import examples from './landing-examples.json'
import { channelLabel, URGENCY_LABEL, type Urgency } from './model'
import { Byline, CheckIcon, EvidenceText, UrgencyPill, Wordmark } from './ui'

// A short hint of what each industry's tickets are about. An industry the bundle does not know shows its name alone.
const INDUSTRY_HINTS: Record<string, string> = {
  'ev-charging': 'Chargers, payments, sessions',
  'e-commerce': 'Orders, delivery, checkout',
  'saas-support': 'Sign-in, API, billing',
}

type Example = (typeof examples)[number]

// The queue button names the choice: "Open the SaaS support queue", "Open the e-commerce queue".
const QUEUE_NAMES: Record<string, string> = { 'e-commerce': 'e-commerce' }
const queueName = (industry: Industry | undefined) => industry ? QUEUE_NAMES[industry.id] ?? industry.name : 'demo'

export type IndustryChoice = { industries: Industry[]; selected: string; onSelect: (id: string) => void }

/**
 * A real result recorded from the live model for the picked industry, shown before anything is opened so a visitor sees
 * what CaseLens does in the first seconds. The examples were captured from the deployed pipeline on the date shown.
 */
function PreviewCard({ example }: { example: Example }) {
  const result = example.result
  const quotes = result.evidence.map((item) => item.quote)
  const placement = placeEvidence(example.subject, example.message, quotes)
  const policy = result.policies[0]
  const found = placement.placed.size
  return (
    <div className="preview-card">
      <div className="pv-head">
        <p className="pv-id"><span className="pv-channel">{channelLabel(example.channel)}</span></p>
        <h2 className="pv-subject"><EvidenceText text={example.subject} marks={placement.subject} /></h2>
        <div className="tags"><UrgencyPill urgency={result.urgency as Urgency} /><span className="tag">Priority <b className="num">{result.priorityScore}</b></span><span className="tag">{result.categoryLabel}</span><span className="badge b-recorded">Recorded AI result</span></div>
      </div>
      <p className="msg pv-msg"><EvidenceText text={example.message} marks={placement.message} /></p>
      <ol className="reasons pv-reasons">{result.evidence.map((item, index) => <li key={index}><span className="ref-chip" aria-hidden="true">{index + 1}</span><span><span className="sr-only">Evidence {index + 1}: </span>{item.meaning}</span></li>)}</ol>
      {policy && <div className="pv-policy"><p className="pv-label">Policy: {policy.label}</p><p>{policy.guidance}</p></div>}
      <p className="pv-check"><CheckIcon />{found} of {quotes.length} quotes found word for word in the ticket</p>
    </div>
  )
}

export function Landing({ onOpen, busy, error, choice, onShowBuilt }: { onOpen: () => void; busy: boolean; error: string | null; choice: IndustryChoice; onShowBuilt: () => void }) {
  const selected = choice.industries.find((industry) => industry.id === choice.selected)
  const example = examples.find((item) => item.industry === choice.selected)
  const name = queueName(selected)
  return (
    <main className="landing">
      <section className="landing-copy" aria-labelledby="caselens-title">
        <Wordmark />
        <h1 id="caselens-title" tabIndex={-1}>Support triage that shows its evidence</h1>
        <p className="claim">CaseLens triages each ticket, matches it to your policies and quotes the exact words it relied on. A person accepts or overrides every call.</p>
        <form className="launch" onSubmit={(event) => { event.preventDefault(); onOpen() }}>
          <fieldset className="industries" disabled={busy}>
            <legend>Pick a sample queue</legend>
            <div className="industry-options">
              {choice.industries.map((industry) => (
                <label key={industry.id} className={industry.id === choice.selected ? 'industry selected' : 'industry'}>
                  <input type="radio" name="industry" value={industry.id} checked={industry.id === choice.selected} onChange={() => choice.onSelect(industry.id)} />
                  <span className="ind-name">{industry.name}</span>
                  {INDUSTRY_HINTS[industry.id] && <span className="ind-hint">{INDUSTRY_HINTS[industry.id]}</span>}
                </label>
              ))}
            </div>
          </fieldset>
          <button type="submit" className="btn btn-primary btn-launch" disabled={busy}>{busy ? `Opening the ${name} queue...` : `Open the ${name} queue`}</button>
          <p className="launch-note">A private demo workspace: recorded AI results to review and tickets to triage live. No sign-in, deleted after 24 hours.</p>
          {busy && <p className="launch-status" role="status">Creating a private workspace with {selected ? `${selected.name} tickets` : 'synthetic tickets'}. First access may take up to 20 seconds while serverless services resume.</p>}
          {error && <p className="error-banner" role="alert">{error}</p>}
        </form>
        <p className="landing-foot"><span>Synthetic tickets only.</span> <button type="button" className="link-button" onClick={onShowBuilt}>How it's built</button></p>
        <Byline />
      </section>
      {example && (
        <section className="landing-preview" aria-labelledby="preview-caption">
          <p className="preview-caption" id="preview-caption">Example: a recorded result from the {selected?.name ?? 'demo'} queue, {URGENCY_LABEL[example.result.urgency as Urgency].toLowerCase()} urgency</p>
          <PreviewCard example={example} />
        </section>
      )}
    </main>
  )
}
