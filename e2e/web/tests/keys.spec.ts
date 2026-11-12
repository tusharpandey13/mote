import { test, expect, DRY, pauseClock } from '../lib/fixtures';
import type { Page } from '@playwright/test';

const mod = (page: Page, bit: number) => page.locator(`.keys [data-mod="${bit}"]`);
const keyEl = (page: Page, usage: number) => page.locator(`.keys .deck [data-key="${usage}"]`);

test.describe('Keys pane', () => {
  test.beforeEach(async ({ remote }) => {
    await remote.openPaired();
    await remote.mode('keys');
  });

  test('sticky modifier: idle -> armed (one-shot) -> locked -> idle', async ({ page, helper }) => {
    await pauseClock(page); // freezes Date.now, so the double-tap window (400 ms) is deterministic
    const shift = mod(page, 2);
    // armed: applies to the next key only
    await shift.tap();
    await expect(shift).toHaveClass(/armed/);
    await expect(page.locator('#chord-mods .mini')).toHaveText(['⇧']);
    let from = helper.mark();
    await keyEl(page, 4).tap(); // A
    await helper.waitFor(DRY.key(4, 2), { from });
    await expect(shift).not.toHaveClass(/armed|locked/);
    from = helper.mark();
    await keyEl(page, 5).tap(); // B, no longer shifted
    await helper.waitFor(DRY.key(5, 0), { from });
    // a second tap later than 400 ms turns an armed modifier off
    await shift.tap();
    await expect(shift).toHaveClass(/armed/);
    await page.clock.runFor(500);
    await shift.tap();
    await expect(shift).not.toHaveClass(/armed|locked/);
    // double tap: locked, applies to every key until tapped again
    await shift.tap();
    await shift.tap();
    await expect(shift).toHaveClass(/locked/);
    await expect(page.locator('#chord-mods .mini.lk')).toHaveText(['⇧']);
    from = helper.mark();
    await keyEl(page, 4).tap();
    await keyEl(page, 5).tap();
    await helper.waitFor(DRY.key(4, 2), { from });
    await helper.waitFor(DRY.key(5, 2), { from });
    await expect(shift).toHaveClass(/locked/);
    await shift.tap();
    await expect(shift).not.toHaveClass(/armed|locked/);
    await expect(page.locator('#chord-mods')).toHaveText('Tap a modifier, double-tap to lock');
    from = helper.mark();
    await keyEl(page, 4).tap();
    await helper.waitFor(DRY.key(4, 0), { from });
  });

  test('chord ⌘⇧4', async ({ page, helper }) => {
    await mod(page, 8).tap();
    await mod(page, 2).tap();
    await expect(page.locator('#chord-mods .mini')).toHaveText(['⇧', '⌘']);
    await expect(page.locator('#chord-next')).toBeVisible();
    const from = helper.mark();
    await keyEl(page, 33).tap(); // 4
    await helper.waitFor(DRY.key(33, 8 | 2), { from });
    expect(helper.since(from, /DRY /)).toHaveLength(1);
    await expect(page.locator('#chord-next')).toBeHidden();
  });

  test('Clear drops armed and locked modifiers', async ({ page, helper }) => {
    await pauseClock(page);
    await mod(page, 1).tap();
    await mod(page, 4).tap(); await mod(page, 4).tap();
    await expect(mod(page, 4)).toHaveClass(/locked/);
    await page.locator('#chord-clear').tap();
    await expect(page.locator('.keys .armed, .keys .locked')).toHaveCount(0);
    const from = helper.mark();
    await keyEl(page, 4).tap();
    await helper.waitFor(DRY.key(4, 0), { from });
  });

  test('F1-F12 and the navigation keys', async ({ page, helper }) => {
    const keys = [58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 74, 75, 76, 77, 78, 82, 80, 81, 79, 41];
    for (const usage of keys) {
      const from = helper.mark();
      await page.locator(`#upper [data-key="${usage}"]`).tap();
      await helper.waitFor(DRY.key(usage), { from });
    }
  });

  test('function block collapses and stays collapsed after reload', async ({ page }) => {
    await page.locator('#fn-tog').tap();
    await expect(page.locator('#upper')).toBeHidden();
    await page.reload();
    await expect(page.locator('.pane[data-mode="keys"]')).toBeVisible(); // the mode is remembered too
    await expect(page.locator('#upper')).toBeHidden();
    await page.locator('#fn-tog').tap();
    await expect(page.locator('#upper')).toBeVisible();
  });

  test('symbol row toggles and sends its keys', async ({ page, helper }) => {
    await expect(page.locator('#k-sym')).toBeHidden();
    await page.locator('[data-sym]').tap();
    await expect(page.locator('#k-sym')).toBeVisible();
    const from = helper.mark();
    await page.locator('#k-sym [data-key="53"]').tap();
    await helper.waitFor(DRY.key(53), { from });
  });

  // Regression: text over 2000 UTF-16 units used to be cut off, and dropped entirely when the cut split an emoji.
  test('long text is sent in pieces without splitting characters', async ({ page, helper }) => {
    const s = 'a'.repeat(1999) + '👋' + 'b'.repeat(500); // the emoji straddles the 2000-unit limit
    const from = helper.mark();
    await page.locator('#k-text').evaluate((el: HTMLInputElement, v) => { el.value = v; }, s);
    await page.locator('#k-form .sendb').tap();
    await helper.waitFor(DRY.text, { from, count: 2 });
    const parts = helper.since(from, DRY.text).map((m) => m[1]);
    expect(parts).toHaveLength(2);
    expect(parts[0]).toBe('a'.repeat(1999));
    expect(parts.join('')).toBe(s);
    expect(helper.since(from, /err|malformed/)).toHaveLength(0);
  });

  test('Unicode text box -> one text event', async ({ page, helper }) => {
    const s = 'héllo ✓ 日本';
    let from = helper.mark();
    const ev0 = helper.eventMark();
    await page.locator('#k-text').fill(s);
    await page.locator('#k-form .sendb').tap();
    const m = await helper.waitFor(DRY.text, { from });
    expect(m[1]).toBe(s);
    await helper.waitEvent('input', 'summary', { from: ev0, fields: /"text":/, timeout: 12_000 }); // DU-2 input/summary: the text itself is never logged
    expect(helper.since(from, /DRY /)).toHaveLength(1);
    await expect(page.locator('#k-text')).toHaveValue('');
    // "send Return after" appends \n, and is remembered.
    await page.locator('#k-ret').check();
    from = helper.mark();
    await page.locator('#k-text').fill('ok');
    await page.locator('#k-text').press('Enter'); // the keyboard's send key submits too
    expect((await helper.waitFor(DRY.text, { from }))[1]).toBe('ok\\n');
    await page.reload();
    await expect(page.locator('#k-ret')).toBeChecked();
  });
});
