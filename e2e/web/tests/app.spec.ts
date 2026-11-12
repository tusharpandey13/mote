import { test, expect } from '../lib/fixtures';
import { Helper } from '../lib/helper';

test.describe('theme', () => {
  test('light/dark toggle applies at once and persists across reloads', async ({ page, remote }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await remote.openPaired();
    const html = page.locator('html');
    await expect(html).not.toHaveClass(/dark/);
    await page.locator('#pill').tap();
    await expect(page.locator('#sheet')).toBeVisible();
    await page.locator('#s-theme [data-theme="dark"]').tap();
    await expect(html).toHaveClass(/dark/);
    await expect(page.locator('#s-theme [data-theme="dark"]')).toHaveClass(/\bon\b/);
    await page.reload();
    await expect(html).toHaveClass(/dark/);
    await expect(page.locator('meta[name=theme-color]')).toHaveAttribute('content', '#222428');
    await page.locator('#pill').tap();
    await page.locator('#s-theme [data-theme="light"]').tap();
    await expect(html).not.toHaveClass(/dark/);
    await page.reload();
    await expect(html).not.toHaveClass(/dark/);
    // System follows the OS setting.
    await page.locator('#pill').tap();
    await page.locator('#s-theme [data-theme="system"]').tap();
    await page.emulateMedia({ colorScheme: 'dark' });
    await expect(html).toHaveClass(/dark/);
  });
});

test.describe('connection', () => {
  test('helper killed mid-session -> disconnected UI -> restarted -> reconnects', async ({ page }) => {
    // Kills its helper, so it gets its own.
    const h = await Helper.start();
    try {
      const cred = await h.pairDevice('e2e kill');
      await page.goto(h.url + '/__seed');
      await page.evaluate((c) => localStorage.setItem('mote.cred.wifi', JSON.stringify(c)), cred);
      await page.goto(h.url + '/');
      await expect(page.locator('#rail')).toBeVisible();

      await h.kill();
      await expect(page.locator('#lost')).toBeVisible();
      await expect(page.locator('#lost-title')).toHaveText(`Reconnecting to ${h.host}…`);
      await expect(page.locator('#stage')).toHaveClass(/offline/); // controls are inert while offline
      await expect(page.locator('#pill-state')).toHaveText(/Offline|Connecting…/);

      // Restarted with an empty --memory-store: the page reconnects on its own and offers its stored token.
      const from = h.mark();
      const ev0 = h.eventMark();
      await h.restart();
      await h.waitEvent('lifecycle', 'start', { from: ev0 });   // RC-8 lifecycle/start: the restarted helper's own events (the web remote has no phone-side diag events)
      await h.waitFor(/auth failed from 127\.0\.0\.1/, { from, timeout: 20_000 });
      // That Mac no longer knows it (a real restart keeps tokens in the Keychain), so pair again.
      await expect(page.locator('#v-pair')).toBeVisible();
      await expect(page.locator('#p-err')).toHaveText("This Mac doesn't recognise this browser any more. Pair again.");
      const code = await h.openPairing();
      await page.locator('#p-code').fill(code);
      await page.locator('#p-go').tap();
      await expect(page.locator('#rail')).toBeVisible();
      await expect(page.locator('#lost')).toBeHidden();
      const m = h.mark();
      await page.locator('[data-cons="play"]').tap();
      await h.waitFor(/DRY consumer play$/, { from: m });
      await h.waitEvent('session', 'authed', { from: ev0 });   // RC-8 session/authed: the page authenticated again after the restart
    } finally {
      await h.stop();
    }
  });

  test('Disconnect and Forget this Mac from the menu', async ({ page, remote }) => {
    await remote.openPaired();
    await page.locator('#menu-btn').tap();
    await page.locator('#s-disc').tap();
    await expect(page.locator('#v-connect')).toBeVisible();
    await expect(page.locator('#c-status')).toHaveText('Disconnected.');
    await page.locator('#c-wifi').tap();
    await expect(page.locator('#rail')).toBeVisible(); // still paired
    await page.locator('#menu-btn').tap();
    await page.locator('#s-forget').tap();
    await expect(page.locator('#v-connect')).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('mote.cred.wifi'))).toBeNull();
  });
});

test.describe('Web Bluetooth', () => {
  test('unavailable in this browser -> explained, Bluetooth disabled, Wi-Fi works', async ({ page, remote }) => {
    await page.addInitScript(() => { Object.defineProperty(Navigator.prototype, 'bluetooth', { get: () => undefined, configurable: true }); });
    await remote.open();
    await expect(page.locator('#v-pair')).toBeVisible();
    await page.locator('#p-back').tap(); // "Use a different connection"
    await expect(page.locator('#v-connect')).toBeVisible();
    await expect(page.locator('#c-ble')).toBeDisabled();
    await expect(page.locator('#c-ble-note')).toHaveText(/^Web Bluetooth isn't available in this browser \(iOS and Firefox don't support it\)\. Use Wi-Fi:/);
    await expect(page.locator('#c-wifi')).toBeEnabled();
    await expect(page.locator('#c-wifi-note')).toHaveText('Connects to the Mac that served this page.');
  });

  test('insecure page -> points to the https remote', async ({ page, remote }) => {
    await page.addInitScript(() => {
      Object.defineProperty(Navigator.prototype, 'bluetooth', { get: () => undefined, configurable: true });
      Object.defineProperty(window, 'isSecureContext', { get: () => false, configurable: true });
    });
    await remote.open();
    await page.locator('#p-back').tap();
    await expect(page.locator('#c-ble')).toBeDisabled();
    await expect(page.locator('#c-ble-note')).toHaveText(/^Bluetooth needs the secure page: open https:\/\/tusharpandey13\.github\.io\/mote\/remote\/ in Chrome/);
  });

  test('available -> Bluetooth enabled with the chooser hint', async ({ page, remote }) => {
    await page.addInitScript(() => {
      Object.defineProperty(Navigator.prototype, 'bluetooth', { get: () => ({ requestDevice: () => Promise.reject(Object.assign(new Error('cancelled'), { name: 'NotFoundError' })) }), configurable: true });
    });
    await remote.open();
    await page.locator('#p-back').tap();
    await expect(page.locator('#c-ble')).toBeEnabled();
    await expect(page.locator('#c-ble-note')).toHaveText('Chrome will ask you to choose your Mac.');
    // Cancelling the device chooser is explained, not an error loop.
    await page.locator('#c-ble').tap();
    await expect(page.locator('#c-status')).toHaveText('No Mac was chosen. Tap Bluetooth to try again.');
  });
});
