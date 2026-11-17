import { test } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * A project's Home page — `/$workspaceName/projects/$projectId/home`.
 *
 * Only the "Recent activity" list is modelled, because that is the one part of
 * this page the estate asserts on. The list merges six backend sources
 * (experiments, optimizations, alert events, dataset versions, prompt versions
 * and a daily trace count) into one chronological strip of rows, each of which
 * is a link into the entity it describes.
 *
 * Nothing in the section carries a `data-testid`, and `<section>` maps to the
 * `region` role only when it has an accessible name — which this one does not —
 * so there is no role handle for the section itself. Everything here is
 * therefore anchored on the one stable thing in it: the "Recent activity"
 * heading. `heading.locator('..')` is a single hop to the section that OWNS
 * that heading, which is identity-addressed rather than positional: it cannot
 * drift onto a sibling section the way an `nth()` would, and it survives the
 * rows being reordered, which they are by `created_at`. A `data-testid` on the
 * section would be better and belongs in a frontend change; this spec is
 * running against a deployed build, which cannot pick one up.
 */
export class ProjectHomePage {
  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    return test.step(`Open the Home page of project ${projectId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/home`);
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for Recent activity to render', async () => {
      await this.recentActivityHeading.waitFor({ state: 'visible' });
      // The section renders a skeleton while `useRecentActivity` is pending and
      // an empty state when the answer carries no items, so neither the heading
      // nor a row count means "the answer has landed". Waiting for one of the
      // two settled shapes is what separates "no activity" from "not yet".
      await Promise.race([
        this.recentActivityRows.first().waitFor({ state: 'visible' }),
        this.recentActivityEmptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  private get recentActivityHeading(): Locator {
    return this.page.getByRole('heading', { name: 'Recent activity', exact: true });
  }

  /** The section that owns the heading — see the class comment on the `..` hop. */
  private get recentActivitySection(): Locator {
    return this.recentActivityHeading.locator('..');
  }

  /** "No activity yet", which the section renders instead of any rows. */
  get recentActivityEmptyState(): Locator {
    return this.recentActivitySection.getByText('No activity yet', { exact: true });
  }

  /**
   * Every row of the list. Returned so a spec can assert the COUNT: "my row is
   * there" would pass just as well over a list that had also grown a row it
   * should not have.
   */
  get recentActivityRows(): Locator {
    return this.recentActivitySection.getByRole('link');
  }

  /**
   * The `trace_daily` row — the one labelled "Traces logged <when>".
   *
   * Matched on the label's stable prefix rather than the whole string, because
   * the rest of it is a relative date (`formatRelativeDateTime`): the row for
   * today reads "Traces logged today", yesterday's "Traces logged yesterday".
   * Which of those it is, is asserted in `tracesLoggedTodayCount`.
   */
  get tracesLoggedRow(): Locator {
    return this.recentActivityRows.filter({ hasText: /Traces logged/ });
  }

  /**
   * The count the "Traces logged today" row renders, as a number.
   *
   * Two claims in one read, and both are assertions rather than conveniences:
   *
   *  - the label says **today**. The row's `created_at` is the server's `now`
   *    for today's bucket and noon UTC for any older day, so a row labelled
   *    anything else is a different day's bucket and comparing its count
   *    against a seed written today would be meaningless.
   *  - the value is a decimal integer. The row renders the server's own string
   *    verbatim, so a count that arrived as `"3 traces"` or an empty label has
   *    to fail here — not be coerced into a number a comparison then agrees
   *    with.
   *
   * Throws rather than returning null on either: a spec that got `null` would
   * have to guard the comparison, and a guarded comparison is one that silently
   * stops checking. The anchored `:` keeps the match on the label's own value
   * and off the relative-date chip the row also carries on its right.
   */
  async tracesLoggedTodayCount(): Promise<number> {
    return test.step('Read the "Traces logged today" count', async () => {
      const row = this.tracesLoggedRow;
      const text = (await row.innerText()).trim();
      const match = /^Traces logged today:\s*(\d+)\b/.exec(text);
      if (match === null) {
        throw new Error(
          'ProjectHomePage.tracesLoggedTodayCount: the Recent activity row did not read ' +
            `"Traces logged today: <n>" — it read ${JSON.stringify(text)}`,
        );
      }
      return Number(match[1]);
    });
  }
}
