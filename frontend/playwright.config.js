import { defineConfig } from '@playwright/test'
import { existsSync } from 'node:fs'
import path from 'node:path'
const localBrowsers = path.resolve('node_modules/.cache/ms-playwright')
if (!process.env.PLAYWRIGHT_BROWSERS_PATH && existsSync(localBrowsers))
  process.env.PLAYWRIGHT_BROWSERS_PATH = localBrowsers
export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results/browser',
  timeout: 90000,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    viewport: { width: 1440, height: 1000 },
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 30000,
  },
})
