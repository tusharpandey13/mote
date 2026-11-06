import { test as base, expect, Page, CDPSession, Locator } from '@playwright/test';
import { Helper, Cred } from './helper';

/** Multi-touch through CDP (real touch -> pointer events with pointerType "touch", one pointerId per finger). */
export class Touch {
  private constructor(private cdp: CDPSession) {}
  static async on(page: Page) { return new Touch(await page.context().newCDPSession(page)); }
  private send(type: string, pts: [number, number][]) {
    return this.cdp.send('Input.dispatchTouchEvent', {
      type: type as any,
      touchPoints: pts.map(([x, y], id) => ({ x, y, id, radiusX: 1, radiusY: 1, force: 1 })),
    });
  }
  start(...pts: [number, number][]) { return this.send('touchStart', pts); }
  move(...pts: [number, number][]) { return this.send('touchMove', pts); }
  end() { return this.send('touchEnd', []); }
  /** Touch down and up, sent back to back (not awaited in between) so a loaded runner can't stretch the tap
   * past the 500 ms tap/hold limit, which the page measures with real event timestamps. */
  tap(...pts: [number, number][]) { return Promise.all([this.start(...pts), this.end()]); }
  /** Moves all fingers by (dx, dy) in `steps` equal integer steps; returns the final points. */
  async drag(from: [number, number][], dx: number, dy: number, steps: number) {
    let pts = from;
    for (let i = 1; i <= steps; i++) {
      pts = from.map(([x, y]) => [x + Math.round((dx * i) / steps), y + Math.round((dy * i) / steps)]);
      await this.move(...pts);
    }
    return pts;
  }
}

export async function center(l: Locator): Promise<[number, number]> {
  const b = (await l.boundingBox())!;
  return [b.x + b.width / 2, b.y + b.height / 2];
}

/** Pauses the page's fake clock (installed by the `page` fixture) so timers only run on clock.runFor. */
export async function pauseClock(page: Page) {
  // pauseAt needs a time ahead of the page's clock, which keeps running while we compute it.
  for (let i = 0; ; i++) {
    try { await page.clock.pauseAt(await page.evaluate(() => Date.now() + 50)); return; } catch (e) {
      if (i >= 5 || !/fast-forward to the past/.test(String(e))) throw e;
    }
  }
}

export type Remote = {
  helper: Helper;
  /** Opens the remote as an already-paired browser (credentials seeded into localStorage). */
  openPaired(): Promise<Cred>;
  /** Opens the remote with whatever this browser has (fresh context: nothing). */
  open(hash?: string): Promise<void>;
  mode(m: 'media' | 'mouse' | 'keys' | 'macros'): Promise<void>;
};

/** Regexes for the helper's dry-run log lines. */
export const DRY = {
  key: (usage: number, mods = 0) => new RegExp(`DRY key usage=${usage} mods=${mods} action=tap$`),
  anyKey: /DRY key usage=(\d+) mods=(\d+) action=(\w+)$/,
  consumer: (k: string) => new RegExp(`DRY consumer ${k}$`),
  move: /DRY move dx=(-?[\d.]+) dy=(-?[\d.]+)$/,
  scroll: /DRY scroll dx=(-?\d+) dy=(-?\d+)$/,
  button: (b: string, action: string, count = 1) => new RegExp(`DRY button ${b} ${action} count=${count}$`),
  anyButton: /DRY button (\w+) (\w+) count=(\d)$/,
  text: /DRY text "(.*)"$/,
};

type TestFixtures = { remote: Remote };
type WorkerFixtures = { helper: Helper };

export const test = base.extend<TestFixtures, WorkerFixtures>({
  // One helper per worker (its tests run sequentially). Tests that kill it or trip its rate limiter start their own.
  helper: [async ({}, use) => {
    const h = await Helper.start();
    await use(h);
    await h.stop();
  }, { scope: 'worker' }],

  page: async ({ page }, use) => {
    await page.clock.install(); // time flows normally until a test pauses it
    // CPU_THROTTLE=4 npx playwright test ...: slow the page down (CDP) to shake out timing flakes.
    if (process.env.CPU_THROTTLE) await (await page.context().newCDPSession(page)).send('Emulation.setCPUThrottlingRate', { rate: +process.env.CPU_THROTTLE });
    await use(page);
  },

  remote: async ({ page, helper }, use) => {
    helper.cmd('resume');
    const r: Remote = {
      helper,
      async open(hash = '') {
        await page.goto(helper.url + '/' + hash);
      },
      async openPaired() {
        const cred = await helper.pairDevice();
        // Same origin, no app code: a 404 page lets us seed localStorage before the remote loads.
        await page.goto(helper.url + '/__seed');
        await page.evaluate((c) => localStorage.setItem('mote.cred.wifi', JSON.stringify(c)), cred);
        const from = helper.mark();
        await page.goto(helper.url + '/');
        await expect(page.locator('#rail')).toBeVisible();
        await helper.waitFor(/authed e2e seed via Wi-Fi/, { from });
        return cred;
      },
      async mode(m) {
        await page.locator(`#rail [data-mode="${m}"]`).tap();
        await expect(page.locator(`.pane[data-mode="${m}"]`)).toBeVisible();
      },
    };
    await use(r);
  },
});

export { expect };
