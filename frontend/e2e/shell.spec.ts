import { expect, test } from '@playwright/test'
import { installApiStubs } from './stubs'

test('completes the reviewer journey from landing to reviewed tickets and the measured views', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', 'The primary journey is covered at desktop width.')

  await installApiStubs(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Support triage that shows its evidence' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Open the SaaS support queue' })).toBeVisible()
  await page.getByRole('radio', { name: /EV charging/ }).check()
  await expect(page.getByText('Example: a recorded result from the EV charging queue', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Open the EV charging queue' }).click()

  // The laptop opens the top recorded ticket at once; nothing is sent to the triage worker for it.
  await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible()
  const detail = page.locator('.detail')
  await expect(detail.getByRole('heading', { name: /Every charger at the demo site appears offline/ })).toBeVisible()
  await expect(detail.getByText('Recorded AI result')).toBeVisible()
  await expect(detail.locator('mark.ev')).toHaveCount(3)
  await expect(detail.getByText('3 of 3 quotes found word for word in the ticket').first()).toBeVisible()
  await detail.getByRole('button', { name: 'Accept' }).click()
  await expect(detail.getByText("The AI's call is logged as confirmed.")).toBeVisible()
  await expect(detail.getByRole('button', { name: 'Override urgency' })).toBeVisible()

  // A ticket that was never triaged runs the live pipeline when it is opened. Its quote names a payment reference
  // the AI only saw redacted, and the page still highlights it in the original text.
  await page.locator('.queue').getByRole('button', { name: /Payment accepted but charging did not start/ }).click()
  await expect(detail.getByText('AI verified')).toBeVisible({ timeout: 15_000 })
  await expect(detail.locator('mark.ev').filter({ hasText: 'PAY-DEMO-7319' })).toHaveCount(1)
  await detail.getByRole('button', { name: 'Override urgency' }).click()
  await detail.getByRole('button', { name: /^High/ }).click()
  await detail.getByLabel('Reason, optional').fill('The driver was charged and is still waiting.')
  await detail.getByRole('button', { name: 'Save' }).click()
  await expect(detail.getByText('Overridden.')).toBeVisible()
  await expect(detail.getByText('AI priority (original)')).toBeVisible()
  await expect(detail.getByRole('button', { name: 'Change review' })).toBeVisible()

  await page.locator('.queue').getByRole('button', { name: /Connector reports a persistent fault/ }).click()
  await expect(detail.locator('.fallback-note')).toContainText("The AI's answer failed a check")

  await page.getByRole('button', { name: "How it's built" }).first().click()
  await expect(page.getByRole('heading', { name: "How it's built" })).toBeVisible()
  await expect(page.getByRole('dialog').getByRole('link', { name: 'Harish Darko' })).toHaveAttribute('href', 'https://harishdarko.com')
  await page.getByRole('button', { name: 'Close', exact: true }).click()

  await page.getByRole('button', { name: 'Evaluation' }).click()
  await expect(page.getByRole('heading', { name: 'Evaluation signal' })).toBeVisible()
  await page.getByRole('button', { name: 'Operations' }).click()
  // The rules fallback above counts as a degraded provider; the retry demo is still offered.
  await expect(page.getByRole('heading', { name: /Provider degraded/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Run controlled retry demo' })).toBeVisible()
})

test('works one-handed on a phone: list, then detail with the review bar in reach', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', 'The responsive journey is covered in the phone project.')

  await installApiStubs(page)
  await page.goto('/')
  const launch = page.getByRole('button', { name: 'Open the SaaS support queue' })
  await expect(launch).toBeInViewport()
  await page.getByRole('radio', { name: /EV charging/ }).check()
  await page.getByRole('button', { name: 'Open the EV charging queue' }).click()
  await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible()
  // The phone opens on the list; nothing is selected until the visitor picks a ticket.
  await expect(page.locator('.detail')).toBeHidden()
  const overflow = () => page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1)
  expect(await overflow()).toBe(false)

  await page.locator('.queue').getByRole('button', { name: /Every charger at the demo site appears offline/ }).click()
  await expect(page.locator('.queue')).toBeHidden()
  const accept = page.getByRole('button', { name: 'Accept' })
  await expect(accept).toBeInViewport()
  // The bar sticks to the foot of the screen while the reviewer scrolls down to the policy and the trace.
  await page.mouse.wheel(0, 1200)
  await expect(accept).toBeInViewport()
  expect(await overflow()).toBe(false)

  await page.getByRole('button', { name: 'Back to inbox' }).click()
  await expect(page.locator('.queue')).toBeVisible()
  await page.getByRole('button', { name: 'Open menu' }).click()
  await expect(page.getByRole('dialog', { name: 'Menu' }).getByRole('link', { name: 'Harish Darko' })).toBeVisible()
})
