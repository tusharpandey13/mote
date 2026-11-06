import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.ts',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  retries: 0, // flaky tests are bugs: no retries
  workers: process.env.CI ? 2 : 4,
  reporter: [['list']],
  use: {
    ...devices['Pixel 7'], // Chromium, mobile viewport, isMobile + hasTouch
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium-mobile', use: { browserName: 'chromium' } }],
});
