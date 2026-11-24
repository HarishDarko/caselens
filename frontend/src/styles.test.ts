import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from 'vitest'

const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf-8')

function luminance(hex: string) {
  const value = hex.replace('#', '')
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value
  const [r, g, b] = [0, 2, 4].map((at) => parseInt(full.slice(at, at + 2), 16) / 255).map((c) => c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const contrast = (a: string, b: string) => { const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x); return (hi + 0.05) / (lo + 0.05) }
const ink = /--ink:\s*(#[0-9a-f]{3,6})/i.exec(css)![1]

test('the urgency hover rule never overrides the pressed button, and the pressed colours hold on hover', () => {
  const hoverRules = css.split('\n').filter((line) => /\.urg-opt[^{]*:hover/.test(line))
  expect(hoverRules.length).toBeGreaterThan(0)
  const plainHover = hoverRules.filter((line) => line.startsWith('.urg-opt:hover'))
  expect(plainHover.length).toBeGreaterThan(0)
  for (const line of plainHover) expect(line).toContain(':not([aria-pressed="true"])')
  const pressedHover = hoverRules.find((line) => line.includes('[aria-pressed="true"]:hover'))
  expect(pressedHover).toBeDefined()
  expect(pressedHover).toMatch(/background: var\(--ink\)/)
  expect(pressedHover).toMatch(/color: #fff/)
})

test('the pressed urgency button and its AI tag keep at least 4.5:1 contrast', () => {
  const tag = /\.urg-opt\[aria-pressed="true"\] \.ai-tag \{ color: (#[0-9a-f]{3,6})/i.exec(css)![1]
  expect(contrast('#ffffff', ink)).toBeGreaterThanOrEqual(4.5)
  expect(contrast(tag, ink)).toBeGreaterThanOrEqual(4.5)
})
