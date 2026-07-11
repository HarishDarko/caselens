import { expect, test } from '@playwright/test'

test('shows the honest CaseLens demo shell', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'CaseLens' })).toBeVisible()
  await expect(page.getByText('Demo environment')).toBeVisible()
})
