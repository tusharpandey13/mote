import { test, expect } from '../lib/fixtures';
import { Helper } from '../lib/helper';

test.describe('pairing and auth', () => {
  test('first open -> pairing screen -> enter the code -> paired', async ({ page, remote, helper }) => {
    const code = await helper.openPairing();
    await remote.open();
    await expect(page.locator('#v-pair')).toBeVisible();
    await expect(page.locator('#p-closed')).toBeHidden();
    await expect(page.locator('#p-name')).toHaveValue('Chrome on Android');
    const from = helper.mark();
    await page.locator('#p-code').fill(code);
    await page.locator('#p-go').tap();
    await expect(page.locator('#rail')).toBeVisible();
    await expect(page.locator('#v-pair')).toBeHidden();
    await helper.waitFor(/paired Chrome on Android \(Wi-Fi\)/, { from });
    await helper.waitFor(/authed Chrome on Android via Wi-Fi from 127\.0\.0\.1/, { from });
    const cred = await page.evaluate(() => JSON.parse(localStorage.getItem('mote.cred.wifi') || 'null'));
    expect(cred.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(cred.token).toMatch(/^[0-9a-f]{64}$/);
    await expect(page.locator('#pill-name')).not.toHaveText('Mote');
  });

  test('pairing closed on the Mac -> the pairing screen says so', async ({ page, remote }) => {
    await remote.open(); // no pairing window open: hello.pairing is false
    await expect(page.locator('#v-pair')).toBeVisible();
    await expect(page.locator('#p-closed')).toBeVisible();
  });

  test('QR link with #code= pairs automatically', async ({ page, remote, helper }) => {
    const code = await helper.openPairing();
    const from = helper.mark();
    await remote.open('#code=' + code);
    await expect(page.locator('#rail')).toBeVisible();
    await helper.waitFor(/paired Chrome on Android/, { from });
    expect(page.url()).not.toContain('code='); // the code is scrubbed from the URL
  });

  test('reload -> automatic re-auth with the stored token', async ({ page, remote, helper }) => {
    await remote.openPaired();
    const from = helper.mark();
    await page.reload();
    await expect(page.locator('#rail')).toBeVisible();
    await expect(page.locator('#v-pair')).toBeHidden();
    await helper.waitFor(/authed e2e seed via Wi-Fi/, { from });
    expect(helper.since(from, /paired /)).toHaveLength(0); // no pairing, the token was reused
  });

  test('revoked on the Mac -> back to pairing, token forgotten', async ({ page, remote, helper }) => {
    await remote.openPaired();
    helper.cmd('revoke');
    await expect(page.locator('#v-pair')).toBeVisible();
    await expect(page.locator('#p-err')).toHaveText("This Mac doesn't recognise this browser any more. Pair again.");
    expect(await page.evaluate(() => localStorage.getItem('mote.cred.wifi'))).toBeNull();
    // Pairing again with a fresh code gets back in.
    const code = await helper.openPairing();
    await page.locator('#p-code').fill(code);
    await page.locator('#p-go').tap();
    await expect(page.locator('#rail')).toBeVisible();
  });

  test('stale token on reload -> pairing screen with an explanation', async ({ page, remote, helper }) => {
    await remote.openPaired();
    const from = helper.mark();
    helper.cmd('revoke'); // the live session is dropped and the stored token is cleared...
    await expect(page.locator('#v-pair')).toBeVisible();
    // ...so put an unknown token back, as a browser that was offline during the revoke would have.
    await page.evaluate(() => localStorage.setItem('mote.cred.wifi', JSON.stringify({ id: '00000000-0000-0000-0000-000000000000', token: 'ab'.repeat(32) })));
    await page.reload();
    await expect(page.locator('#v-pair')).toBeVisible();
    await expect(page.locator('#p-err')).toHaveText("This Mac doesn't recognise this browser any more. Pair again.");
    await helper.waitFor(/auth failed from 127\.0\.0\.1/, { from });
  });
});

test.describe('wrong code and rate limit', () => {
  // Trips the per-peer limiter (blocks 127.0.0.1 for 60 s), so it gets its own helper.
  let h: Helper;
  test.beforeAll(async () => { h = await Helper.start(); });
  test.afterAll(async () => { await h.stop(); });

  test('wrong code, then the rate-limit message', async ({ page }) => {
    const code = await h.openPairing();
    const wrong = code === '000000' ? '111111' : '000000';
    await page.goto(h.url + '/');
    await expect(page.locator('#v-pair')).toBeVisible();
    const err = page.locator('#p-err');
    for (let i = 1; i <= 5; i++) {
      const from = h.mark();
      await page.locator('#p-code').fill(wrong);
      await page.locator('#p-go').tap();
      await expect(err).toHaveText('Wrong code. Check the code on the Mac and try again.');
      await h.waitFor(/pairing: wrong code from 127\.0\.0\.1/, { from });
      await expect(page.locator('#p-go')).toBeEnabled();
      if (i < 5) await page.locator('#p-code').fill(''); // the message stays until the next result
    }
    await page.locator('#p-code').fill(code); // even the right code is refused while limited
    await page.locator('#p-go').tap();
    await expect(err).toHaveText(/^Too many attempts\. Try again in \d+s\.$/);
    await expect(page.locator('#rail')).toBeHidden();
  });
});
