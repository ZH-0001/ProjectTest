import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './tests',
  timeout: 60000,
  workers: 1,
  fullyParallel: false,
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5174', channel: 'chrome', viewport: { width: 1440, height: 1000 }, trace: 'off', screenshot: 'off', video: 'off' },
  webServer: [
    { command: 'powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/start-test-backend.ps1', url: 'http://127.0.0.1:8081/user/csrf', reuseExistingServer: !process.env.CI, timeout: 120000 },
    { command: 'npm run dev -- --port 5174', env: { API_TARGET: 'http://127.0.0.1:8081' }, url: 'http://127.0.0.1:5174', reuseExistingServer: !process.env.CI, timeout: 60000 },
  ],
})
