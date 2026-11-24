import { useEffect, useRef, type ReactNode } from 'react'
import type { EvaluationSummary, FailurePage, OperationsOverview } from './api'
import { humanize } from './model'
import { CheckIcon } from './ui'

// Quality and operations: one screen for "are live jobs healthy, how good is the model, and can I recover a failure?"
// It reads the same three endpoints Evaluation and Operations read before (evaluation, operations overview, failures),
// together, once per refresh.

export type LiveState = 'loading' | 'live' | 'paused' | 'failed' | 'ended'

const LIVE_STATUS_TEXT: Record<LiveState, string> = {
  loading: 'Loading the latest numbers for this workspace. The first load can take up to 20 seconds while the service starts.',
  live: 'Live: updates every 5 seconds while this tab is open.',
  paused: 'Live updates are paused because there was no activity for 10 minutes.',
  failed: 'Could not load the latest numbers. The service may be starting or busy. Trying again in 5 seconds.',
  ended: 'Your demo session has ended, so live updates stopped.',
}

const SYNTHETIC = 'SYNTHETIC_PROVIDER_TIMEOUT'

/** A value not measured yet: a dash for the eye, words for a screen reader. Never 0% or "null ms". */
function Empty() {
  return <><span aria-hidden="true">-</span><span className="sr-only">Not measured yet</span></>
}
const isNumber = (value: number | null | undefined): value is number => typeof value === 'number' && Number.isFinite(value)
function Percent({ value }: { value: number | null | undefined }) {
  return isNumber(value) ? <>{Math.round(value * 100)}%</> : <Empty />
}
function Millis({ value }: { value: number | null | undefined }) {
  return isNumber(value) ? <>{value} ms</> : <Empty />
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

// Only the state sentence is a live region, so a screen reader speaks when the state changes, not on every refresh.
export function LiveStatus({ state, lastUpdated, onRefresh }: { state: LiveState; lastUpdated: Date | null; onRefresh: () => void }) {
  const time = lastUpdated ? lastUpdated.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : null
  return (
    <p className={state === 'live' || state === 'loading' ? 'live-status' : 'live-status paused'}>
      <span className="live-dot" aria-hidden="true" />
      <span role="status">{LIVE_STATUS_TEXT[state]}</span>
      {time && <span className="live-updated"> Last updated at {time}.</span>}
      {state === 'paused' && <> <button type="button" className="link-button" onClick={onRefresh}>Refresh now</button></>}
    </p>
  )
}

type Tone = 'plain' | 'coral' | 'amber' | 'teal'
function Figure({ label, value, detail, tone = 'plain' }: { label: string; value: ReactNode; detail: string; tone?: Tone }) {
  return <div className={`qo-figure tone-${tone}`}><span className="qo-label">{label}</span><strong className="qo-value num">{value}</strong><span className="qo-detail">{detail}</span></div>
}

type RecentJob = OperationsOverview['recent'][number]
const STATUS_LABEL: Record<RecentJob['status'], string> = {
  QUEUED: 'Queued', PROCESSING: 'Processing', COMPLETED: 'Completed', RETRYABLE_FAILURE: 'Retrying', TERMINAL_FAILURE: 'Failed',
}
// The retry demo's job is stopped on purpose and acknowledged, so SQS is not retrying it: it is not "Retrying".
const statusLabel = (job: RecentJob) => job.status === 'RETRYABLE_FAILURE' && job.lastErrorCode === SYNTHETIC ? 'Stopped on purpose' : STATUS_LABEL[job.status]
// Pending only while a job can still finish; a failed or stopped job has no latency to wait for.
const stillRunning = (job: RecentJob) => job.status === 'QUEUED' || job.status === 'PROCESSING' || (job.status === 'RETRYABLE_FAILURE' && job.lastErrorCode !== SYNTHETIC)
function JobLatency({ job }: { job: RecentJob }) {
  if (isNumber(job.latencyMs)) return <>{job.latencyMs} ms</>
  return stillRunning(job) ? <>Pending</> : <Empty />
}

export type RecoveryState = { pending: boolean; busy: boolean }

export function QualityOps({ evaluation, overview, failures, live, loading, recovery, focusToken, announcement, onSimulate, onRetry }: {
  evaluation: EvaluationSummary | null
  overview: OperationsOverview | null
  failures: FailurePage | null
  live: ReactNode
  /** The first numbers are on their way; the live line says so, and the page shows placeholders. */
  loading: boolean
  recovery: RecoveryState
  /** Changes after Refresh now, Simulate provider timeout and Retry case: focus goes to the recovery banner heading. */
  focusToken: number
  announcement: string
  onSimulate: () => void
  onRetry: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!focusToken) return
    // The effect runs after the new banner is in the DOM, so focus can move at once.
    ;(ref.current?.querySelector<HTMLElement>('#qo-recovery-title') ?? ref.current?.querySelector<HTMLElement>('h1'))?.focus({ preventScroll: true })
  }, [focusToken])
  // This screen's own polite region: recovery steps are announced here; the live line keeps its state sentence.
  const said = <p className="sr-only" aria-live="polite">{announcement}</p>
  const head = (health?: { text: string; tone: Tone }) => (
    <header className="qo-head">
      <div className="qo-title"><h1 tabIndex={-1}>Quality and operations</h1>{health && <span className={`qo-health tone-${health.tone}`}>{health.text}</span>}</div>
      {live}
    </header>
  )
  // Before the first numbers arrive the live line carries the state (loading, failed, paused or ended); nothing repeats it.
  if (!evaluation || !overview) return <div className="qo" ref={ref}>{head()}{loading && <div className="qo-skeleton" aria-hidden="true"><span /><span /><span /></div>}{said}</div>

  const count = failures?.totalElements ?? 0
  const firstFailure = failures?.content[0]
  const synthetic = firstFailure?.lastErrorCode === SYNTHETIC
  const queue = overview.queue
  const inFlight = queue.queued + queue.processing
  // The failures still waiting for a retry, so the card agrees with the banner; a replayed job leaves the list.
  const failed = count
  const failedJobs = queue.retryableFailures + queue.terminalFailures
  const health: { text: string; tone: Tone } = count ? { text: 'Recovery required', tone: 'coral' }
    : overview.providerFailureCount > 0 ? { text: 'Degraded', tone: 'amber' } : { text: 'Healthy', tone: 'teal' }
  const reviewed = evaluation.reviewedResults
  // An API from before recorded results sends no reviewer figures; its correction rate is the nearest it has.
  const reviewerFigures = evaluation.reviewedResults !== undefined
  const overrideRate = reviewerFigures ? evaluation.overrideRate : evaluation.triageResults > 0 ? evaluation.correctionRate : null
  const recorded = evaluation.recordedResults
  const recent = overview.recent.slice(0, 20)
  const providers = overview.providers
  // An older API sends 0 for a rate it has not measured; the scored count tells them apart.
  const benchmark = evaluation.evaluatedResults > 0 ? evaluation.agreementRate : null
  const p50 = queue.completed > 0 ? evaluation.medianLatencyMs : null
  const p95 = queue.completed > 0 ? evaluation.p95LatencyMs : null

  return (
    <div className="qo" ref={ref}>
      {head(health)}
      {said}

      <section className={count ? 'qo-recovery tone-coral' : 'qo-recovery'} aria-labelledby="qo-recovery-title">
        <span className="qo-recovery-icon" aria-hidden="true">{count ? '!' : <CheckIcon />}</span>
        <div className="qo-recovery-text">
          {count ? <>
            <h2 id="qo-recovery-title" tabIndex={-1}>{synthetic ? 'Recovery required' : `${count} failed job${count === 1 ? '' : 's'} need${count === 1 ? 's' : ''} a retry`}</h2>
            <p>{synthetic ? 'A controlled synthetic timeout was recorded. Retry the case to verify the recovery path.' : `Job ${firstFailure?.id ?? ''} stays here until a reviewer retries it.`}</p>
          </> : recovery.pending ? <>
            <h2 id="qo-recovery-title" tabIndex={-1}>Simulating a provider timeout</h2>
            <p>A demo ticket is on its way to a provider that never answers. The worker records the timeout in a few seconds, and it shows here for a retry.</p>
          </> : <>
            <h2 id="qo-recovery-title" tabIndex={-1}>No unresolved failures</h2>
            <p>Simulate provider timeout sends one demo ticket to a provider that never answers, so you can watch the failure land here and retry it.</p>
          </>}
        </div>
        {count
          ? <button type="button" className="btn btn-primary" onClick={onRetry} disabled={recovery.busy}>{synthetic ? 'Retry case' : 'Retry failed case'}</button>
          : <button type="button" className="btn btn-secondary" onClick={onSimulate} disabled={recovery.busy || recovery.pending}>Simulate provider timeout</button>}
      </section>

      <section className="qo-block" aria-labelledby="qo-queue-title">
        <h2 id="qo-queue-title" className="qo-block-title">Live queue and reliability</h2>
        <div className="qo-figures">
          <Figure label="In flight" value={inFlight} detail={`${queue.queued} queued, ${queue.processing} processing`} />
          <Figure label="Completed live" value={queue.completed} detail="Live worker runs; recorded results are not counted" tone={queue.completed ? 'teal' : 'plain'} />
          <Figure label="Recoverable failures" value={failed} detail={failed ? `Waiting for a retry here; ${failedJobs} failed job${failedJobs === 1 ? '' : 's'} in all` : `None waiting; ${failedJobs} failed job${failedJobs === 1 ? '' : 's'} in all`} tone={failed ? 'coral' : 'plain'} />
          <Figure label="Rules fallbacks" value={overview.fallbackCount} detail={`${overview.providerFailureCount} provider failure${overview.providerFailureCount === 1 ? '' : 's'}`} tone={overview.fallbackCount ? 'amber' : 'plain'} />
        </div>
      </section>

      <section className="qo-block" aria-labelledby="qo-quality-title">
        <h2 id="qo-quality-title" className="qo-block-title">Model quality and review</h2>
        <div className="qo-figures">
          <Figure label="Reviewer acceptance" value={<Percent value={evaluation.acceptanceRate} />} detail={!reviewerFigures ? 'Needs a newer backend' : isNumber(reviewed) && reviewed > 0 ? `${evaluation.acceptedResults ?? 0} of ${reviewed} reviewed results accepted` : 'No reviews yet'} />
          <Figure label="Override rate" value={<Percent value={overrideRate} />} detail={reviewerFigures ? 'Reviews that changed the urgency' : 'Corrections as a share of results'} tone={overrideRate ? 'coral' : 'plain'} />
          <Figure label="Benchmark match" value={<Percent value={benchmark} />} detail={`${evaluation.evaluatedResults} result${evaluation.evaluatedResults === 1 ? '' : 's'} scored against the answer key`} />
          <Figure label="Live latency" value={<>p50 <Millis value={p50} /></>} detail={isNumber(p95) ? `p95 ${p95} ms` : 'p95 not measured yet'} />
        </div>
        <p className="qo-note">
          Benchmark match compares each result with its scenario's hidden answer key; acceptance and overrides are what reviewers did, and the two are kept apart.
          {isNumber(recorded) && recorded > 0 && (reviewed === 0 ? ` 0 of ${recorded} recorded tickets reviewed.` : ` Recorded results count toward benchmark match and reviews, never toward the live queue, latency or provider figures.`)}
        </p>
      </section>

      <section className="qo-block" aria-labelledby="qo-log-title">
        <h2 id="qo-log-title" className="qo-block-title">Recent live jobs</h2>
        {recent.length ? <ol className="qo-log">
          {recent.map((job) => (
            <li key={job.jobId} className="qo-log-row">
              <div className="qo-log-main">
                <span className="qo-log-line">
                  <b>{statusLabel(job)}</b>
                  {job.decisionSource && <span className={job.decisionSource === 'AI_VALIDATED' ? 'badge b-verified' : 'badge b-fallback'}>{job.decisionSource === 'AI_VALIDATED' ? 'AI verified' : 'Rules fallback'}</span>}
                  {job.lastErrorCode && <span className="badge b-error">{humanize(job.lastErrorCode)}</span>}
                </span>
                <span className="qo-log-meta">{[job.provider ? humanize(job.provider) : 'Not called', job.modelVersion].filter(Boolean).join(' · ')} · {job.attemptCount} attempt{job.attemptCount === 1 ? '' : 's'} · updated {formatTime(job.updatedAt)}</span>
              </div>
              <span className="qo-log-latency num"><JobLatency job={job} /></span>
            </li>
          ))}
        </ol> : <p className="qo-empty">No live worker runs yet. Open any untriaged ticket in the Inbox to run it live, or use Simulate provider timeout above to test failure recovery.</p>}
      </section>

      <section className="qo-block" aria-labelledby="qo-provider-title">
        <h2 id="qo-provider-title" className="qo-block-title">Provider usage</h2>
        {providers.length ? <ul className="qo-providers">
          {providers.map((provider) => (
            <li key={provider.provider}>
              <span><b>{humanize(provider.provider)}</b> · {provider.requests} call{provider.requests === 1 ? '' : 's'} · {provider.successes} succeeded · {provider.failures} failed · {provider.inputTokens + provider.outputTokens} tokens</span>
              <span className="num">{provider.averageLatencyMs === null ? 'avg not measured yet' : `avg ${provider.averageLatencyMs} ms`}</span>
              <span className="qo-bar" aria-hidden="true"><span style={{ width: provider.requests ? `${Math.round((provider.successes / provider.requests) * 100)}%` : '0%' }} /></span>
            </li>
          ))}
        </ul> : <p className="qo-empty">No provider calls yet. Counts and latency only; prompts, answers and keys never appear here.</p>}
        <details className="qo-runbook">
          <summary>How retries and dead-letter recovery work</summary>
          <ol>
            <li><b>Retryable.</b> A failed attempt goes back to SQS, which redelivers it on its own.</li>
            <li><b>Terminal.</b> After the last attempt the job is recorded as failed and shows above.</li>
            <li><b>Human recovery.</b> A reviewer retries it here; only failed or acknowledged work is replayed.</li>
          </ol>
        </details>
      </section>
    </div>
  )
}
