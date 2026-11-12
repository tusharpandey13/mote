import { test, expect, DRY, Touch, center, pauseClock } from '../lib/fixtures';
import type { Page } from '@playwright/test';

const sum = (ms: RegExpMatchArray[], i: number) => ms.reduce((a, m) => a + +m[i], 0);

test.describe('Mouse pane', () => {
  let t: Touch;
  let o: [number, number];
  test.beforeEach(async ({ page, remote }) => {
    await remote.openPaired();
    await remote.mode('mouse');
    // Hide the hints overlay so the pad centre is free, then start gestures near the middle of the pad.
    await page.locator('#hints-btn').tap();
    await expect(page.locator('#hints')).toBeHidden();
    const [x, y] = await center(page.locator('#touchpad'));
    o = [Math.round(x), Math.round(y)];
    t = await Touch.on(page);
  });
  const chip = (page: Page) => page.locator('#chip-last');

  test('1-finger move -> coalesced relative moves', async ({ page, helper }) => {
    await pauseClock(page); // the 12 ms coalescing timer only fires when we advance time
    const from = helper.mark();
    await t.start(o);
    const end = await t.drag([o], 100, -40, 20); // 20 pointer moves
    await expect(chip(page)).toHaveText('Move');
    await expect.poll(() => page.evaluate(() => document.getElementById('glow')!.style.left)).toBe(
      `${end[0][0] - (await page.locator('#touchpad').boundingBox())!.x}px`);
    await page.clock.runFor(20);
    await helper.waitFor(DRY.move, { from });
    await t.end();
    const sentinel = helper.mark();
    await page.locator('[data-btn="left"]').tap();
    await helper.waitFor(DRY.button('left', 'click'), { from: sentinel });
    const moves = helper.since(from, DRY.move);
    expect(moves.length).toBeLessThan(20); // coalesced, not one message per pointer event
    // Pointer acceleration: gain is between 1.6x and 4x of the finger travel.
    const dx = sum(moves, 1), dy = sum(moves, 2);
    expect(dx).toBeGreaterThanOrEqual(100 * 1.6 - 1);
    expect(dx).toBeLessThanOrEqual(100 * 4 + 1);
    expect(dy).toBeLessThanOrEqual(-40 * 1.6 + 1);
    expect(dy).toBeGreaterThanOrEqual(-40 * 4 - 1);
    expect(helper.since(from, /DRY button/)).toHaveLength(1); // a move is not a click
  });

  test('tap -> left click', async ({ page, helper }) => {
    await pauseClock(page); // the 500 ms hold timer must not turn a slow tap into a right click
    const from = helper.mark();
    const ev0 = helper.eventMark();
    await t.tap(o);
    await helper.waitFor(DRY.button('left', 'click', 1), { from });
    await expect(chip(page)).toHaveText('Click');
    expect(helper.since(from, DRY.anyButton)).toHaveLength(1);
    await helper.waitEvent('input', 'summary', { from: ev0, fields: /"button":/, timeout: 12_000 }); // DU-2 input/summary: counted, never per event
  });

  test('first tap soon after page load is a single click', async ({ page, helper }) => {
    // Regression: lastTapAt started at 0 and e.timeStamp counts from page load, so a first tap within 300 ms of
    // load was sent as count=2. Pin the timestamps to model that tap deterministically.
    const from = helper.mark();
    await page.locator('#touchpad').evaluate((pad, [x, y]) => {
      for (const [type, ts] of [['pointerdown', 100], ['pointerup', 120]] as const) {
        const ev = new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: 9, pointerType: 'touch', isPrimary: true, clientX: x, clientY: y });
        Object.defineProperty(ev, 'timeStamp', { value: ts });
        pad.dispatchEvent(ev);
      }
    }, o);
    await helper.waitFor(DRY.anyButton, { from });
    expect(helper.since(from, DRY.anyButton).map((m) => m[0].replace(/^.*DRY /, ''))).toEqual(['button left click count=1']);
    await expect(chip(page)).toHaveText('Click');
  });

  test('double tap -> double click', async ({ page, helper }) => {
    const from = helper.mark();
    // The 300 ms double-tap window uses real event timestamps; dispatch both taps in one page task so load can't
    // stretch the gap.
    await page.locator('#touchpad').evaluate((pad, [x, y]) => {
      for (const type of ['pointerdown', 'pointerup', 'pointerdown', 'pointerup']) {
        pad.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: 9, pointerType: 'touch', isPrimary: true, clientX: x, clientY: y }));
      }
    }, o);
    await helper.waitFor(DRY.button('left', 'click', 2), { from });
    expect(helper.since(from, DRY.anyButton).map((m) => m[0].replace(/^.*DRY /, ''))).toEqual(['button left click count=1', 'button left click count=2']);
    await expect(chip(page)).toHaveText('Double click');
  });

  test('2-finger tap -> right click', async ({ page, helper }) => {
    await pauseClock(page);
    const from = helper.mark();
    await t.tap(o, [o[0] + 40, o[1]]);
    await helper.waitFor(DRY.button('right', 'click'), { from });
    await expect(chip(page)).toHaveText('Right click');
    expect(helper.since(from, /DRY /)).toHaveLength(1);
  });

  test('hold -> right click', async ({ page, helper }) => {
    await pauseClock(page);
    const from = helper.mark();
    await t.start(o);
    await expect(page.locator('#glow')).toBeVisible();
    await page.clock.runFor(510);
    await helper.waitFor(DRY.button('right', 'click'), { from });
    await t.end();
    await expect(page.locator('#glow')).toBeHidden();
    expect(helper.since(from, /DRY button/)).toHaveLength(1); // no extra left click on release
  });

  test('2-finger scroll -> scroll events, natural direction', async ({ page, helper }) => {
    await pauseClock(page); // scrolls are batched per animation frame
    const from = helper.mark();
    const pts: [number, number][] = [[o[0] - 30, o[1] + 60], [o[0] + 30, o[1] + 60]];
    await t.start(...pts);
    await t.drag(pts, 0, -60, 6); // both fingers up 60 px
    await expect(chip(page)).toHaveText('Scroll');
    await t.end();
    await expect(page.locator('#glow')).toBeHidden(); // pointerup handled, so every move before it was too
    await page.clock.runFor(50); // flush the per-frame scroll batch
    await helper.waitFor(DRY.scroll, { from });
    const sentinel = helper.mark();
    await page.locator('[data-btn="left"]').tap();
    await helper.waitFor(DRY.button('left', 'click'), { from: sentinel });
    const s = helper.since(from, DRY.scroll);
    expect(sum(s, 1)).toBe(0);
    expect(sum(s, 2)).toBe(120); // content follows the fingers: up = view moves down, 2 fingers x 60
    expect(helper.since(from, DRY.move)).toHaveLength(0);
    expect(helper.since(from, DRY.anyButton)).toHaveLength(1); // only the sentinel
  });

  test('3-finger swipe -> switch Space', async ({ page, helper }) => {
    const from = helper.mark();
    const pts: [number, number][] = [[o[0] - 40, o[1]], [o[0], o[1]], [o[0] + 40, o[1]]];
    await t.start(...pts);
    await t.drag(pts, -80, 0, 8);
    await t.end();
    await helper.waitFor(DRY.key(79, 1), { from }); // swipe left -> next Space (⌃→)
    await expect(chip(page)).toHaveText('Next space');
  });

  test('drag lock: button down, moves are drags, unlock -> button up', async ({ page, helper }) => {
    let from = helper.mark();
    await page.locator('#m-lock').tap();
    await helper.waitFor(DRY.button('left', 'down'), { from });
    await expect(page.locator('#m-lock')).toHaveClass(/\bon\b/);
    await expect(page.locator('#touchpad')).toHaveClass(/locked/);
    from = helper.mark();
    await t.start(o);
    await t.drag([o], 60, 0, 6);
    await t.end();
    await helper.waitFor(DRY.move, { from });
    await expect(chip(page)).toHaveText('Drag');
    const sentinel = helper.mark();
    await page.locator('#m-lock').tap();
    await helper.waitFor(DRY.button('left', 'up'), { from: sentinel });
    await expect(page.locator('#m-lock')).not.toHaveClass(/\bon\b/);
    await expect(chip(page)).toHaveText('Drag lock off');
    expect(helper.since(from, /DRY button left click/)).toHaveLength(0);
  });

  test('Left / Right buttons click', async ({ page, helper }) => {
    let from = helper.mark();
    await page.locator('[data-btn="left"]').tap();
    await helper.waitFor(DRY.button('left', 'click'), { from });
    from = helper.mark();
    await page.locator('[data-btn="right"]').tap();
    await helper.waitFor(DRY.button('right', 'click'), { from });
  });
});
