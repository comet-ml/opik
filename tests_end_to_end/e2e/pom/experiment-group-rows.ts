import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * The grouped experiments table, as it renders on BOTH surfaces that show one:
 * the project Experiments page and the prompt detail page's Experiments tab.
 *
 * Shared rather than written twice because it is one component — the same
 * `DataTable` fed by the same `useGroupedExperimentsList`, with the same
 * aggregated cells — reached through two pages. opik#9646's fix had to be
 * applied at both call sites (`GeneralDatasetsTab` and `ExperimentsTab`), and a
 * regression can land on one and not the other, so the two specs that cover
 * them must be asserting through identical mechanics or a difference in the
 * POMs would read as a difference in the product.
 *
 * ## How a group row is addressed
 *
 * `DataTable` stamps `data-row-id` with TanStack's row id. For a GROUP row that
 * is `<grouping column id>:<hash of the grouped value>` — an internal key this
 * object deliberately does not reconstruct. What it uses instead is the
 * `$__grouping__` prefix (every group row's id starts with it; an experiment
 * row's starts with the experiment's own uuid) plus the item source's name,
 * which is how a reader identifies the group on screen and is unique inside a
 * run's namespace. Every lookup asserts it resolved to exactly ONE row, so an
 * ambiguous name fails loudly instead of silently testing a different group.
 *
 * Cells are addressed by `data-cell-id`, which is `<row id>_<column id>` — by
 * identity, never by position, because column order here is user-configurable
 * and persisted.
 */
export class ExperimentGroupRows {
  constructor(private readonly page: Page) {}

  /**
   * Every group row currently rendered.
   *
   * `GROUPING_KEY` from the frontend's `constants/groups.ts`. Not
   * `$__grouping_row__`, which prefixes the synthetic "load more"/"pending"
   * rows and does not collide with this (`$__grouping_` is followed by `_`
   * here and by `r` there).
   */
  get groupRows(): Locator {
    return this.page.locator('tbody tr[data-row-id^="$__grouping__"]');
  }

  /** The one group row for an item source, by the name it shows. */
  groupRow(itemSourceName: string): Locator {
    return this.groupRows.filter({
      has: this.page.getByRole('link', { name: itemSourceName, exact: true }),
    });
  }

  /**
   * One group row's Pass rate cell.
   *
   * Matched on the column id suffix rather than the whole `data-cell-id`, so the
   * row id itself never has to be reconstructed. Scoped to the row, so the
   * experiment rows inside an expanded group — which have a `_pass_rate` cell of
   * their own — cannot be picked up.
   */
  groupPassRateCell(itemSourceName: string): Locator {
    return this.groupRow(itemSourceName).locator('td[data-cell-id$="_pass_rate"]');
  }

  /**
   * One experiment's own row, wherever it sits.
   *
   * Under grouping, `getExperimentRowId` composes the row id as
   * `<experimentId>|<grouping field>:<value>|…`, because the same experiment can
   * appear under more than one group. Anchored at the start on the experiment id
   * so the match is the experiment's identity and nothing about the group's
   * internal key.
   */
  experimentRow(experimentId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id^="${experimentId}|"]`);
  }

  /** One experiment row's Pass rate cell. */
  experimentPassRateCell(experimentId: string): Locator {
    return this.experimentRow(experimentId).locator('td[data-cell-id$="_pass_rate"]');
  }

  /** Block until the table has rendered group rows at all. */
  async waitForGroupRows(expected: number): Promise<void> {
    await test.step(`wait for ${expected} group row(s)`, async () => {
      await expect(this.groupRows, 'group rows on the grouped experiments table').toHaveCount(
        expected,
        { timeout: 30_000 },
      );
    });
  }

  /**
   * Assert a group row's Pass rate cell, label and status dot together.
   *
   * The pairing is the point. A label on its own cannot catch the defect
   * `formatPercent`'s cap exists to prevent — "100%" beside a RED dot, on a
   * group that is one item short of perfect — and a dot on its own cannot catch
   * the wrong number. `toHaveText` is an equality, so asserting '67%' also
   * rules out the pooled '50%' and an empty cell.
   *
   * The dot's colour is read off the inline `style` the component writes
   * (`backgroundColor: var(--color-…)`) rather than a computed colour, so the
   * assertion names the branch that was taken instead of a theme's hex value.
   */
  async expectGroupPassRate(
    itemSourceName: string,
    expected: { label: string; dot: 'passed' | 'failed' },
  ): Promise<void> {
    await test.step(`group '${itemSourceName}' reads ${expected.label} with a ${expected.dot} dot`, async () => {
      const cell = this.groupPassRateCell(itemSourceName);
      await expect(cell, `exactly one Pass rate cell on group '${itemSourceName}'`).toHaveCount(
        1,
        { timeout: 30_000 },
      );
      await expect(cell, `group '${itemSourceName}' Pass rate`).toHaveText(expected.label, {
        timeout: 30_000,
      });
      const dot = cell.locator('span[style*="background-color"]');
      await expect(dot, `exactly one status dot on group '${itemSourceName}'`).toHaveCount(1);
      await expect(dot, `group '${itemSourceName}' status dot colour`).toHaveAttribute(
        'style',
        expected.dot === 'passed'
          ? /background-color:\s*var\(--color-green-bright\)/
          : /background-color:\s*var\(--color-red\)/,
      );
    });
  }

  /**
   * Assert a group row shows NO pass rate — an empty cell, not "0%".
   *
   * Both halves are asserted: the cell's text is empty AND no pill was rendered
   * inside it. Text alone would also be satisfied by a pill that rendered with
   * no label, which is a different defect wearing the same appearance.
   */
  async expectGroupPassRateEmpty(itemSourceName: string): Promise<void> {
    await test.step(`group '${itemSourceName}' shows no pass rate`, async () => {
      const cell = this.groupPassRateCell(itemSourceName);
      await expect(cell, `exactly one Pass rate cell on group '${itemSourceName}'`).toHaveCount(
        1,
        { timeout: 30_000 },
      );
      await expect(cell, `group '${itemSourceName}' Pass rate`).toHaveText('');
      await expect(
        cell.locator('span[style*="background-color"]'),
        `no status dot on group '${itemSourceName}'`,
      ).toHaveCount(0);
    });
  }

  /**
   * Hover a group row's pass-rate pill and assert its tooltip, in full.
   *
   * The tooltip is two lines, and the whole text is asserted as one anchored
   * regex so that both the wording and the ORDER are pinned and nothing extra
   * can be present. The counts are the POOLED items, which the wording must not
   * present as the percentage's source — the percentage is the mean of the
   * runs' rates, and quoting 2 / 4 as its origin beside "67%" is precisely the
   * wrongness this wording replaced.
   *
   * Radix portals the content out of the row, so it is looked up at page scope
   * by role and asserted to be the only one open. The tooltip is dismissed
   * afterwards because its positioning wrapper covers neighbouring rows and
   * would swallow the next hover.
   */
  async expectGroupPassRateTooltip(
    itemSourceName: string,
    expected: { passed: number; total: number },
  ): Promise<void> {
    await test.step(`group '${itemSourceName}' pill tooltip names the average and ${expected.passed} / ${expected.total}`, async () => {
      const pill = this.groupPassRateCell(itemSourceName).locator('div[data-state]');
      const tooltip = await this.openPillTooltip(pill, `group '${itemSourceName}'`);

      await expect(tooltip, `group '${itemSourceName}' pill tooltip`).toHaveText(
        new RegExp(
          `^Average of the experiments' pass rates\\s*${expected.passed} / ${expected.total} items passed in total$`,
        ),
        { timeout: 10_000 },
      );

      await this.dismissTooltip();
    });
  }

  /**
   * Hover a pill until its tooltip is actually open, and hand it back.
   *
   * The retry is not a workaround for a slow tooltip — `toHaveCount` already
   * waits that out. It is for a LOST `pointerenter`. The grouped table settles
   * over several requests (the groups, their aggregations, and each expanded
   * group's experiments) and the Experiments page polls on top of that, so React
   * can replace the pill's DOM node while the pointer is resting on it. Radix
   * opens on `pointerenter`, and swapping the node under a stationary pointer
   * crosses no boundary — so the tooltip never opens and no amount of waiting
   * will change that. Leaving and re-entering is what makes it open.
   *
   * Each attempt parks the pointer away from the pill first, so the next move is
   * a real boundary crossing rather than a move to the coordinates it is already
   * at (which Chromium does not report as an enter).
   */
  private async openPillTooltip(pill: Locator, label: string): Promise<Locator> {
    const tooltip = this.page.getByRole('tooltip');
    await expect(pill, `exactly one pass-rate pill on ${label}`).toHaveCount(1, {
      timeout: 30_000,
    });
    await expect
      .poll(
        async () => {
          if ((await tooltip.count()) > 0) await this.dismissTooltip();
          await this.page.mouse.move(0, 0);
          await pill.hover();
          try {
            // Short, so a lost enter is retried rather than waited out. Radix
            // opens on its own ~700ms delay, well inside this.
            await tooltip.first().waitFor({ state: 'visible', timeout: 2_000 });
          } catch {
            return 0;
          }
          return tooltip.count();
        },
        { timeout: 30_000, message: `exactly one tooltip raised by ${label}` },
      )
      .toBe(1);
    return tooltip;
  }

  /**
   * Close whatever tooltip is open, and wait until it is really gone.
   *
   * Moving the pointer away is not enough: Radix's popper wrapper is wider than
   * the visible tooltip and keeps intercepting pointer events, so the next
   * `hover()` fails with "subtree intercepts pointer events". Escape is the
   * dismissal `TooltipWrapper` honours.
   */
  async dismissTooltip(): Promise<void> {
    await this.page.keyboard.press('Escape');
    await expect(this.page.getByRole('tooltip'), 'the tooltip after Escape').toHaveCount(0, {
      timeout: 10_000,
    });
  }

  /**
   * Assert an experiment row's own Pass rate tag — the filled one, as opposed to
   * the group row's status-dot pill.
   */
  async expectExperimentPassRate(experimentId: string, expectedLabel: string): Promise<void> {
    await test.step(`experiment ${experimentId} reads ${expectedLabel}`, async () => {
      const cell = this.experimentPassRateCell(experimentId);
      await expect(cell, `exactly one Pass rate cell on experiment ${experimentId}`).toHaveCount(
        1,
        { timeout: 30_000 },
      );
      await expect(cell, `experiment ${experimentId} Pass rate`).toHaveText(expectedLabel, {
        timeout: 30_000,
      });
    });
  }

  /**
   * Expand a group so its experiment rows render, and wait for one of them.
   *
   * Idempotent, and that matters: the page auto-expands one group on load
   * (`generateAutoExpandMap` only expands groups whose id starts with the first
   * group's, so with several groups exactly the first one opens), and clicking
   * an already-open group's toggle would COLLAPSE it. The expected experiment
   * row's presence is what decides — a behavioural signal, rather than reading
   * which way a chevron icon happens to point.
   *
   * The toggle is addressed as "the group row's only button": its accessible
   * name is the item-source PREFIX ("Test suite", "Dataset", or "Item source"),
   * which `resolveGroupPrefix` picks per group and which changes as the group's
   * rows load — so naming it would be addressing a moving target.
   */
  async expandGroup(itemSourceName: string, experimentIdInGroup: string): Promise<void> {
    await test.step(`expand group '${itemSourceName}'`, async () => {
      const row = this.groupRow(itemSourceName);
      await expect(row, `exactly one group row for '${itemSourceName}'`).toHaveCount(1, {
        timeout: 30_000,
      });
      if ((await this.experimentRow(experimentIdInGroup).count()) > 0) return;

      const toggle = row.getByRole('button');
      await expect(toggle, `exactly one expand toggle on group '${itemSourceName}'`).toHaveCount(
        1,
      );
      await toggle.click();
      await expect(
        this.experimentRow(experimentIdInGroup),
        `experiment ${experimentIdInGroup} after expanding '${itemSourceName}'`,
      ).toHaveCount(1, { timeout: 30_000 });
    });
  }
}
