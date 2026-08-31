import { test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * Column ids on the project-scoped Optimization runs list, as declared in
 * `OptimizationsColumns.tsx`. The shared `DataTable` stamps every cell
 * `data-cell-id="<rowId>_<columnId>"`, so addressing by column id follows the
 * column wherever a user has dragged it — this table's order is user-configurable
 * and persisted, so a positional selector would read the wrong cell.
 *
 * Note `accuracy` is the id of the column now LABELLED "Best score": the id was
 * kept through the rename so existing users' saved column state survived.
 */
export type OptimizationListColumn =
  | 'name'
  | 'status'
  | 'accuracy'
  | 'latency'
  | 'cost'
  | 'opt_cost';

/**
 * The Optimization runs list at `/projects/$projectId/optimizations` — the read
 * side only. Launching a run and reading a single run's detail page both live on
 * `OptimizationStudioPage`.
 */
export class OptimizationsListPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('Open the Optimization runs list', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations`,
      );
    });
  }

  /**
   * Wait for one named run's row to render.
   *
   * Deliberately NOT "wait for any row": while the list loads it paints a screen
   * of empty skeleton rows, and on a cloud deployment that can persist for the
   * better part of half a minute. Anything gating on row *count* passes against
   * skeletons and then reads empty cells, so the gate has to be the row this
   * test is about.
   */
  async waitForRun(optimizationId: string, timeoutMs = 90_000): Promise<void> {
    return test.step(`Wait for optimization run ${optimizationId}`, async () => {
      await this.runRow(optimizationId).waitFor({ state: 'visible', timeout: timeoutMs });
    });
  }

  /** Every populated row. Skeleton placeholders carry no `data-row-id`. */
  get runRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /** One run's row, keyed by optimization id rather than by its rendered name. */
  runRow(optimizationId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${optimizationId}"]`);
  }

  /** One cell of a run's row, addressed by column id. */
  cell(optimizationId: string, column: OptimizationListColumn): Locator {
    return this.page.locator(`td[data-cell-id="${optimizationId}_${column}"]`);
  }

  /**
   * The metric name on the Best score cell's feedback-score tag — the objective
   * the number belongs to. Worth asserting alongside the value: a cell showing
   * the right number under the wrong metric name is still wrong.
   */
  bestScoreLabel(optimizationId: string): Locator {
    return this.cell(optimizationId, 'accuracy').getByTestId('feedback-score-tag-label');
  }

  /** The number on the Best score cell's feedback-score tag. */
  bestScoreValue(optimizationId: string): Locator {
    return this.cell(optimizationId, 'accuracy').getByTestId('feedback-score-tag-value');
  }
}
