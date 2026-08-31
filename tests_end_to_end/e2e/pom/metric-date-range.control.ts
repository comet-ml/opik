import { test, expect, type Page, type Locator } from '@playwright/test';

/**
 * The MetricDateRangeSelect control, shared by every surface that windows its
 * data by date — the project Logs tabs and the project Dashboards page here.
 *
 * Not a page: it is one control that several pages mount, so it lives on its
 * own and each page object exposes it rather than re-declaring the locators.
 */

/** The preset labels the control offers, per PRESET_LABEL_MAP in the FE. */
export const DATE_RANGE_PRESETS = [
  'Past 24 hours',
  'Past 3 days',
  'Past 7 days',
  'Past 30 days',
  'Past 60 days',
  'All time',
] as const;

export type DateRangePresetLabel = (typeof DATE_RANGE_PRESETS)[number];

/**
 * localStorage keys the control persists into. Logs and Dashboards keep
 * *separate* slots — the Logs tabs share `local-time_range` (from the default
 * `time_range` query key) while Dashboards passes its own
 * `opik-project-insights-daterange` — so a range picked on one does not move
 * the other.
 *
 * A project may also get its own slot, `<key>-<projectName>`: the seeded demo
 * project does, so its 24h default is not outranked by whatever range the user
 * last picked elsewhere. Ordinary projects must write to the bare key.
 */
export const LOGS_DATE_RANGE_KEY = 'local-time_range';
export const DASHBOARDS_DATE_RANGE_KEY = 'opik-project-insights-daterange';

/** The storage key a project scoped to its own slot would use. */
export const scopedDateRangeKey = (baseKey: string, projectName: string): string =>
  `${baseKey}-${projectName}`;

/**
 * Read a persisted range. `use-local-storage-state` JSON-encodes, so the raw
 * entry is `"past30days"` (quotes included) — this returns the decoded preset
 * id, or null when the key was never written.
 */
export async function readStoredDateRange(page: Page, key: string): Promise<string | null> {
  return page.evaluate((k) => {
    const raw = window.localStorage.getItem(k);
    if (raw === null) return null;
    try {
      return JSON.parse(raw) as string;
    } catch {
      return raw;
    }
  }, key);
}

export class MetricDateRangeControl {
  constructor(private readonly page: Page) {}

  /**
   * The trigger, matched on the preset it currently displays.
   *
   * It is a Radix Select trigger (`role=combobox`) carrying no accessible name
   * or testid, and pages mount other selects, so the displayed preset is the
   * only thing that identifies it. That is sound for preset ranges — the only
   * ones these specs drive — but would not match a custom date range, which
   * renders the dates instead of a label.
   */
  get trigger(): Locator {
    return this.page
      .getByRole('combobox')
      .filter({ hasText: new RegExp(`^(${DATE_RANGE_PRESETS.join('|')})$`) });
  }

  /** The preset currently shown on the trigger. */
  async readPreset(): Promise<string> {
    return test.step('Read the selected date range', async () => {
      await this.trigger.waitFor({ state: 'visible' });
      return ((await this.trigger.textContent()) ?? '').trim();
    });
  }

  /** Assert the trigger shows `label`, retrying while the page settles. */
  async expectPreset(label: DateRangePresetLabel): Promise<void> {
    return test.step(`Expect the date range to read "${label}"`, async () => {
      await expect(this.trigger).toHaveText(label);
    });
  }

  /**
   * Pick a preset and wait for the trigger to adopt it, so callers never read
   * the control mid-update.
   */
  async selectPreset(label: DateRangePresetLabel): Promise<void> {
    return test.step(`Select the "${label}" date range`, async () => {
      await this.trigger.click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
      await expect(this.trigger).toHaveText(label);
    });
  }
}
