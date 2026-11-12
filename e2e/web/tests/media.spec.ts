import { test, expect, DRY, Touch, center, pauseClock } from '../lib/fixtures';

test.describe('Media pane', () => {
  test.beforeEach(async ({ remote }) => {
    await remote.openPaired();
    await remote.mode('media');
  });

  test('every media control sends its consumer key', async ({ page, helper }) => {
    await pauseClock(page); // volume/brightness auto-repeat after 400 ms: a slow tap must not add repeats
    const ev0 = helper.eventMark();
    for (const k of ['prev', 'play', 'next', 'voldown', 'mute', 'volup', 'brightdown', 'brightup']) {
      const from = helper.mark();
      await page.locator(`.pane[data-mode="media"] [data-cons="${k}"]`).tap();
      await helper.waitFor(DRY.consumer(k), { from });
      expect(helper.since(from, /DRY /)).toHaveLength(1); // exactly one event per tap
    }
    // DU-1: the helper's own diag events: the messages arrived, counted (never logged per key)
    await helper.waitEvent('protocol', 'in', { from: ev0, fields: /"t":"consumer"/ });
    await helper.waitEvent('input', 'summary', { from: ev0, fields: /"consumer":/, timeout: 12_000 });
  });

  test('D-pad arrows, OK and esc send their keys', async ({ page, helper }) => {
    await pauseClock(page); // arrows auto-repeat after 400 ms: a slow tap must not add repeats
    const keys: [string, number][] = [['.dpad [data-key="82"]', 82], ['.dpad [data-key="79"]', 79], ['.dpad [data-key="81"]', 81],
      ['.dpad [data-key="80"]', 80], ['.dpad [data-key="40"]', 40], ['.nav-row .kc[data-key="41"]', 41]];
    const ev0 = helper.eventMark();
    for (const [sel, usage] of keys) {
      const from = helper.mark();
      await page.locator(sel).tap();
      await helper.waitFor(DRY.key(usage), { from });
      expect(helper.since(from, /DRY /)).toHaveLength(1);
    }
    await helper.waitEvent('protocol', 'in', { from: ev0, fields: /"t":"key"/ });   // DU-1
    await helper.waitEvent('input', 'summary', { from: ev0, fields: /"key":/, timeout: 12_000 });
  });

  test('holding a D-pad arrow auto-repeats until release', async ({ page, helper }) => {
    const t = await Touch.on(page);
    await pauseClock(page);
    const from = helper.mark();
    await t.start(await center(page.locator('.dpad [data-key="81"]')));
    await helper.waitFor(DRY.key(81), { from });
    await page.clock.runFor(800); // repeat starts at 400 ms, then every 120 ms: 520, 640, 760
    await helper.waitFor(DRY.key(81), { from, count: 4 });
    await t.end();
    await expect(page.locator('.dpad [data-key="81"]')).not.toHaveClass(/hit/);
    await page.clock.runFor(1000);
    const sentinel = helper.mark();
    await page.locator('.nav-row .kc[data-key="41"]').tap();
    await helper.waitFor(DRY.key(41), { from: sentinel });
    expect(helper.since(from, DRY.key(81))).toHaveLength(4); // nothing after release
  });

  test('scroll lever: hold + drag scrolls, faster further out, stops on release', async ({ page, helper }) => {
    const t = await Touch.on(page);
    const thumb = page.locator('#lever-thumb');
    const [cx, cy] = await center(thumb);
    const x = Math.round(cx), y = Math.round(cy);
    const max = await page.evaluate(() => {
      const l = document.getElementById('lever')!, th = document.getElementById('lever-thumb')!;
      return Math.max(20, (l.clientHeight - th.offsetHeight) / 2 - 6);
    });
    // Input reaches the page asynchronously; wait for the thumb to show the displacement before advancing time.
    const dragTo = async (d: number) => {
      await t.move([x, y + d]);
      await expect(thumb).toHaveAttribute('style', new RegExp(`translateY\\(calc\\(-50% \\${d < 0 ? '-' : '+'} ${Math.abs(d)}px\\)\\)`));
    };
    await pauseClock(page);
    const from = helper.mark();
    await t.start([x, y]);
    await expect(thumb).toHaveClass(/pressed/);
    await page.clock.runFor(500); // held but not moved: inside the dead zone, nothing is sent
    await dragTo(Math.round(max * 0.3));
    const m1 = helper.mark();
    await page.clock.runFor(500); // the lever ticks every 50 ms -> 10 scrolls
    await helper.waitFor(DRY.scroll, { from: m1, count: 10 });
    const slow = helper.since(m1, DRY.scroll).map((m) => +m[2]);
    await dragTo(Math.round(max));
    const m2 = helper.mark();
    await page.clock.runFor(500);
    await helper.waitFor(DRY.scroll, { from: m2, count: 10 });
    const fast = helper.since(m2, DRY.scroll).map((m) => +m[2]);
    expect(helper.since(from, DRY.scroll)).toHaveLength(20);
    expect(new Set(slow).size).toBe(1);
    expect(slow[0]).toBeGreaterThan(0); // dragging down scrolls down
    expect(fast).toEqual(Array(10).fill(80)); // full travel = 80 px per tick, more than the slow rate
    expect(slow[0]).toBeLessThan(80);
    // Up is negative.
    await dragTo(-Math.round(max));
    const m3 = helper.mark();
    await page.clock.runFor(100);
    await helper.waitFor(DRY.scroll, { from: m3, count: 2 });
    expect(helper.since(m3, DRY.scroll).map((m) => +m[2])).toEqual([-80, -80]);
    // Release stops it.
    await t.end();
    await expect(thumb).not.toHaveClass(/pressed/);
    const rel = helper.mark();
    await page.clock.runFor(1000);
    await page.locator('.nav-row .kc[data-key="41"]').tap(); // sentinel: everything sent before it is logged before it
    await helper.waitFor(DRY.key(41), { from: rel });
    expect(helper.since(rel, DRY.scroll)).toHaveLength(0);
  });

  // Regression: a second finger used to start a second ticker (double rate) that kept throwing after release.
  test('scroll lever: a second finger neither doubles the rate nor keeps it running', async ({ page, helper }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    const t = await Touch.on(page);
    const thumb = page.locator('#lever-thumb');
    const [cx, cy] = await center(thumb);
    const x = Math.round(cx), y = Math.round(cy);
    await pauseClock(page);
    await t.start([x, y]);
    await expect(thumb).toHaveClass(/pressed/);
    await t.start([x, y], [x + 6, y + 4]);
    await t.move([x, y + 60], [x + 6, y + 64]);
    await expect(thumb).toHaveAttribute('style', /translateY\(calc\(-50% \+ \d+px\)\)/);
    const from = helper.mark();
    await page.clock.runFor(500);
    await t.end();
    await expect(thumb).not.toHaveClass(/pressed/);
    await page.clock.runFor(500);
    await page.locator('.nav-row .kc[data-key="41"]').tap(); // sentinel
    await helper.waitFor(DRY.key(41), { from });
    expect(helper.since(from, DRY.scroll)).toHaveLength(10); // one 50 ms ticker, stopped on release
    expect(errors).toEqual([]);
  });

  test('Spaces favourite: tap -> ⌃↑ (Mission Control)', async ({ page, helper }) => {
    const from = helper.mark();
    const t = await Touch.on(page);
    await t.start(await center(page.locator('.favs [data-spaces]')));
    await t.end();
    await helper.waitFor(DRY.key(82, 1), { from });
  });

  test('Spotlight and Mission favourites', async ({ page, helper }) => {
    let from = helper.mark();
    await page.locator('.favs [data-macro="spotlight"]').tap();
    await helper.waitFor(DRY.key(44, 8), { from });
    from = helper.mark();
    await page.locator('.favs [data-macro="mission"]').tap();
    await helper.waitFor(DRY.key(82, 1), { from });
  });

  test('input paused on the Mac -> notice shown, input refused; resumed -> works again', async ({ page, helper }) => {
    helper.cmd('pause');
    await expect(page.locator('#notice')).toBeVisible();
    await expect(page.locator('#notice-tx')).toHaveText('Input paused on the Mac');
    // DU-5: a key sent while paused is refused by the helper, and the helper logged why
    const ev0 = helper.eventMark();
    await page.locator('[data-cons="play"]').tap();
    await helper.waitEvent('input', 'blocked', { from: ev0, fields: /"reason":"paused"/ });
    helper.cmd('resume');
    await expect(page.locator('#notice')).toBeHidden();
    const from = helper.mark();
    await page.locator('[data-cons="play"]').tap();
    await helper.waitFor(DRY.consumer('play'), { from });
  });
});
