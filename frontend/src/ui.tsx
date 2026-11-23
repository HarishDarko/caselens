import { Fragment, type ReactNode } from 'react'
import { segments, type EvidenceMark } from './evidence'
import { URGENCY_LABEL, type Urgency } from './model'

/** The CaseLens mark: a lens with one highlighted line of text seen through it. */
export function LensMark() {
  return (
    <svg className="mark" viewBox="0 0 28 28" aria-hidden="true" focusable="false">
      <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2.4" />
      <rect x="6" y="10.2" width="12" height="3.6" rx="1" fill="#087f72" />
      <path d="M18.6 18.6 25 25" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" />
    </svg>
  )
}

export function Wordmark() {
  return <div className="wordmark"><LensMark /><span>CaseLens</span></div>
}

export function CheckIcon() {
  return <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M3.5 8.5 6.5 11.5 12.5 4.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
}

export function DashIcon() {
  return <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 8h8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
}

export function CloseIcon() {
  return <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
}

export function UrgencyPill({ urgency }: { urgency: Urgency }) {
  return <span className={`pill u-${urgency.toLowerCase()}`}>{URGENCY_LABEL[urgency]}</span>
}

export const HARISH_SITE = 'https://harishdarko.com'
export const SOURCE_URL = 'https://github.com/HarishDarko/caselens'

export function Byline({ children }: { children?: ReactNode }) {
  return <p className="byline">Designed and built by <a href={HARISH_SITE} target="_blank" rel="noopener">Harish Darko</a>{children}</p>
}

/**
 * A ticket text with its evidence quotes highlighted in place. Each highlight ends with the numbers of the reasons
 * it supports; the last word stays on the same line as its number.
 */
export function EvidenceText({ text, marks, active, onActive }: { text: string; marks: EvidenceMark[]; active?: number | null; onActive?: (ref: number | null) => void }) {
  return (
    <>
      {segments(text, marks).map((segment, index) => {
        if (!segment.refs) return <Fragment key={index}>{segment.text}</Fragment>
        const refs = segment.refs
        const cut = segment.text.trimEnd().lastIndexOf(' ') + 1
        const on = active !== null && active !== undefined && refs.includes(active)
        return (
          <mark key={index} className={on ? 'ev on' : 'ev'} data-ev={refs.join(' ')} onMouseEnter={() => onActive?.(refs[0])} onMouseLeave={() => onActive?.(null)}>
            {segment.text.slice(0, cut)}
            <span className="nw">{segment.text.slice(cut)}{refs.map((ref) => <sup className="ref" key={ref}><span className="sr-only"> evidence </span>{ref}</sup>)}</span>
          </mark>
        )
      })}
    </>
  )
}
