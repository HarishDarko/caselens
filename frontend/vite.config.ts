import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { cloudflareBeacon } from './cf-beacon'

export default defineConfig({
  plugins: [react(), cloudflareBeacon()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
  },
})
