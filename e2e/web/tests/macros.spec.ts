import { test, expect, DRY, Touch, center, pauseClock } from '../lib/fixtures';

test.describe('Macros pane', () => {
  test.beforeEach(async ({ remote }) => {
    await remote.openPaired();
    await remote.mode('macros');
  });
  const tile = (page: any, name: string) => page.locator(`.macros [data-macro="${name}"]`);

  test('single-shortcut tiles send their chord', async ({ page, helper }) => {
    const cases: [string, number, number][] = [
      ['spotlight', 44, 8], ['mission', 82, 1], ['shot', 33, 8 | 2], ['forcequit', 41, 8 | 4], ['hide', 11, 8 | 4],
    ];
    for (const [name, usage, mods] of cases) {
      const from = helper.mark();
      await tile(page, name).tap();
      await helper.waitFor(DRY.key(usage, mods), { from });
      expect(helper.since(from, /DRY /)).toHaveLength(1);
    }
  });

  test('multi-step macro runs in order on the Mac', async ({ page, helper }) => {
    const from = helper.mark();
    await tile(page, 'safari').tap();
    await expect(tile(page, 'safari')).toHaveClass(/running/);
    await expect(tile(page, 'safari').locator('.ch')).toHaveText('Running… tap to cancel');
    await helper.waitFor(/macro: 5 steps/, { from });
    await helper.waitFor(DRY.key(40, 0), { from });
    const steps = helper.since(from, /DRY (.*)$/).map((m) => m[1]);
    expect(steps).toEqual(['key usage=44 mods=8 action=tap', 'text "Safari"', 'key usage=40 mods=0 action=tap']);
    await expect(tile(page, 'safari')).not.toHaveClass(/running/);
    await expect(tile(page, 'safari').locator('.ch')).toHaveText('3 steps');
  });

  test('tapping a running macro cancels it', async ({ page, helper }) => {
    await pauseClock(page); // keep the tile in its running state until we tap it again
    const from = helper.mark();
    // The second tap must land inside the macro's 300 ms wait, which runs in real time on the helper. Under load,
    // two separately driven taps can be further apart than that, so both are dispatched in one page task.
    await tile(page, 'safari').evaluate((el) => {
      for (const type of ['pointerdown', 'pointerup', 'pointerdown', 'pointerup']) {
        el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: 7, pointerType: 'touch', isPrimary: true }));
      }
    });
    await helper.waitFor(/macro: cancelled/, { from });
    await expect(tile(page, 'safari')).not.toHaveClass(/running/);
    expect(helper.since(from, DRY.text)).toHaveLength(0); // cancelled during its 300 ms wait
  });

  test('Lock screen asks for a second tap', async ({ page, helper }) => {
    await pauseClock(page);
    const lock = tile(page, 'lock');
    const from = helper.mark();
    await lock.tap();
    await expect(lock).toHaveClass(/confirm/);
    await expect(lock.locator('.nm')).toHaveText('Tap again');
    await page.clock.runFor(3100); // the confirmation times out
    await expect(lock).not.toHaveClass(/confirm/);
    await expect(lock.locator('.nm')).toHaveText('Lock screen');
    await tile(page, 'spotlight').tap(); // sentinel: the unconfirmed taps sent nothing
    await helper.waitFor(DRY.key(44, 8), { from });
    expect(helper.since(from, DRY.key(20, 9))).toHaveLength(0);
    await lock.tap();
    await lock.tap();
    await helper.waitFor(DRY.key(20, 1 | 8), { from }); // ⌃⌘Q
    await expect(lock).not.toHaveClass(/confirm/);
  });

  test('Spaces tile: hold-slide right/left -> ⌃→/⌃←, tap -> ⌃↑', async ({ page, helper }) => {
    const t = await Touch.on(page);
    const sp = page.locator('.macros [data-spaces]');
    const [cx, cy] = await center(sp);
    const x = Math.round(cx), y = Math.round(cy);
    const slide = helper.mark();
    let from = slide;
    await t.start([x, y]);
    await expect(sp).toHaveClass(/pressed/);
    await t.drag([[x, y]], 100, 0, 5); // 2 steps of 48 px
    await helper.waitFor(DRY.key(79, 1), { from, count: 2 });
    await t.drag([[x + 100, y]], -110, 0, 5); // back to x-10: 2 steps the other way
    await helper.waitFor(DRY.key(80, 1), { from, count: 2 });
    await t.end();
    await expect(sp).not.toHaveClass(/pressed/);
    from = helper.mark();
    await t.start([x, y]);
    await t.end();
    await helper.waitFor(DRY.key(82, 1), { from }); // a tap is Mission Control
    // the slide sent exactly 2+2 steps and did not also count as a tap
    expect(helper.since(slide, DRY.anyKey).map((m) => `${m[1]}/${m[2]}`)).toEqual(['79/1', '79/1', '80/1', '80/1', '82/1']);
  });
});
