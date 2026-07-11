import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, test } from 'vitest'
import App from './App'

test('renders the honest CaseLens demo shell', () => {
  render(<MemoryRouter><App /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: 'CaseLens' })).toBeInTheDocument()
  expect(screen.getByText('Demo environment')).toBeInTheDocument()
})
