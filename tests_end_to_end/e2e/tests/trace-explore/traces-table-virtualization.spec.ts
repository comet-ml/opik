import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Logs table stops rendering every row and every column once it grows past
 * a threshold, and instead renders a moving window of each. Both specs below
 * exist because the failure mode of a window is *silence*: a row dropped or
 * repeated at a seam, or a spacer of the wrong width shifting every cell one
 * column left, renders as a perfectly plausible table. Nothing throws, nothing
 * looks broken, and the numbers on screen belong to the wrong thing.
 *
 * So neither spec asserts "the table rendered". Each pins the rendered window
 * against the answer the API gave for the same query: the rows must be a
 * contiguous slice of the API's ordering with no repeats, and every row's cell
 * sequence must match the header's position for position.
 */

/** Vertical scroll step, in pixels — several rows at a time, small enough that no window is skipped. */
const SCROLL_STEP_PX = 250;

/** Fractions of the horizontal scroll extent to sample the column window at. */
const HORIZONTAL_SAMPLE_FRACTIONS = [0, 0.25, 0.5, 0.75, 1];

test.describe('Traces table row virtualization', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'Scrolling a windowed traces table renders every seeded trace exactly once, in the API order',
    { tag: ['@cap:traces.list-traces'] },
    async ({ windowedTraces, project, page }) => {
      const logs = new LogsPage(page);
      const { orderedIds, count } = windowedTraces;

      await test.step('Confirm the API lists exactly the seeded traces', () => {
        // The whole spec compares the rendered window against this list, so a
        // list that was short, padded or duplicated would make every assertion
        // below pass while checking nothing.
        expect(orderedIds).toHaveLength(count);
        expect(new Set(orderedIds).size).toBe(count);
      });

      await test.step('Open Logs and confirm the table is windowed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();

        // The count card reports the full set even though only part of it is
        // in the DOM — that gap is the precondition for everything below.
        expect(await logs.countTraces()).toBe(count);
        const rendered = await logs.traceRows.count();
        expect(rendered).toBeGreaterThan(0);
        expect(rendered).toBeLessThan(count);
      });

      const seen = new Map<string, number>();

      await test.step('Sweep from top to bottom, checking each window', async () => {
        const { maxTop } = await logs.readTableScrollExtent();
        expect(maxTop).toBeGreaterThan(0);

        const offsets: number[] = [];
        for (let top = 0; top < maxTop; top += SCROLL_STEP_PX) offsets.push(top);
        offsets.push(maxTop);

        for (const top of offsets) {
          await logs.scrollTableTo({ top });
          const rendered = await logs.readTraceIdsInOrder();

          expect(
            new Set(rendered).size,
            `scrollTop=${top}: the same trace is in the DOM twice`,
          ).toBe(rendered.length);

          // A window is only correct if it is a *contiguous* run of the
          // ordering, starting where it claims to: anchoring on the first
          // rendered id and comparing the whole run catches a row silently
          // dropped mid-window, which a set comparison would not.
          const start = orderedIds.indexOf(rendered[0]);
          expect(start, `scrollTop=${top}: first rendered row is not a seeded trace`).toBeGreaterThanOrEqual(0);
          expect(rendered, `scrollTop=${top}: rendered rows are not a contiguous slice of the API order`).toEqual(
            orderedIds.slice(start, start + rendered.length),
          );

          for (const id of rendered) seen.set(id, (seen.get(id) ?? 0) + 1);
        }
      });

      await test.step('Confirm the sweep reached every seeded trace', async () => {
        expect([...seen.keys()].sort()).toEqual([...orderedIds].sort());
        // The last window has to be the tail of the ordering, or the sweep
        // stopped early and "saw everything" only by accident of overscan.
        const finalWindow = await logs.readTraceIdsInOrder();
        expect(finalWindow[finalWindow.length - 1]).toBe(orderedIds[count - 1]);
      });
    },
  );
});

test.describe('Traces table column virtualization', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'Turning on every column keeps headers, cells and colgroup aligned across the column window',
    { tag: ['@cap:traces.configure-columns'] },
    async ({ wideTracesTable, windowedTraces, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { scoreNames } = wideTracesTable;

      await test.step('Confirm the seeded traces really carry every score name', async () => {
        // Each distinct score name is one more column, so this is what makes
        // the table wide enough for the window to engage. If the seed only
        // half-landed the table would fit on screen and the alignment
        // assertions below would be checking an unwindowed table.
        const trace = await backendClient.getTrace(windowedTraces.orderedIds[0]);
        expect(trace, 'the seeded trace is not readable').not.toBeNull();
        expect(new Set(trace!.feedbackScores.map((score) => score.name))).toEqual(
          new Set(scoreNames),
        );
      });

      await test.step('Open Logs and turn every column on', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();

        const before = await logs.readColumnsSelection();
        expect(before.total).toBeGreaterThanOrEqual(scoreNames.length);

        await logs.selectAllColumns();

        const after = await logs.readColumnsSelection();
        expect(after.selected).toBe(after.total);
        expect(after.total).toBe(before.total);
        // Past fifty columns is where the column window engages at all.
        expect(after.total).toBeGreaterThan(50);
      });

      await test.step('Confirm the column window is active', async () => {
        await logs.scrollTableTo({ left: 0 });
        // Without a spacer in the header row every column is in the DOM, and a
        // sequence comparison across a fully-rendered table cannot fail.
        await expect(logs.headerColumnSpacers.first()).toBeVisible();
        const header = await logs.readHeaderColumnSequence();
        expect(header.length).toBeLessThan((await logs.readColumnsSelection()).total);
      });

      await test.step('Check header, cell and colgroup alignment across the width', async () => {
        const { maxLeft } = await logs.readTableScrollExtent();
        expect(maxLeft, 'the table is not wider than its container').toBeGreaterThan(0);

        const sampledHeaders: string[] = [];

        for (const fraction of HORIZONTAL_SAMPLE_FRACTIONS) {
          const left = Math.round(maxLeft * fraction);
          await logs.scrollTableTo({ left });

          const header = await logs.readHeaderColumnSequence();
          const rows = await logs.readRenderedRowColumnSequences();
          const colgroup = await logs.countColgroupColumns();

          expect(rows.length, `scrollLeft=${left}: no rows rendered`).toBeGreaterThan(0);
          expect(
            colgroup,
            `scrollLeft=${left}: the colgroup declares a different number of columns than the header renders`,
          ).toBe(header.length);

          for (const row of rows) {
            expect(
              row.columns,
              `scrollLeft=${left}: row ${row.traceId} is misaligned with the header`,
            ).toEqual(header);
          }

          sampledHeaders.push(header.join(','));
        }

        // Alignment holding at five offsets means nothing if the same columns
        // were rendered at all five — that is a table that never windowed, and
        // it agrees with itself trivially.
        expect(
          new Set(sampledHeaders).size,
          'the column window did not move across the scroll extent',
        ).toBeGreaterThan(1);
      });

      await test.step('Confirm the pinned select column stays at the container edge', async () => {
        const { maxLeft } = await logs.readTableScrollExtent();
        await logs.scrollTableTo({ left: maxLeft });

        const geometry = await logs.readPinnedSelectColumnGeometry();
        expect(geometry.header, 'the select column header is not rendered').not.toBeNull();
        expect(geometry.header!.position).toBe('sticky');
        expect(geometry.header!.left).toBe('0px');
        expect(geometry.header!.viewportLeft).toBe(geometry.containerLeft);

        expect(geometry.rows.length).toBeGreaterThan(0);
        for (const row of geometry.rows) {
          expect(row.position, `row ${row.traceId} select cell is not sticky`).toBe('sticky');
          expect(row.left, `row ${row.traceId} select cell is not pinned to 0`).toBe('0px');
          expect(
            row.viewportLeft,
            `row ${row.traceId} select cell drifted from the container edge`,
          ).toBe(geometry.containerLeft);
        }
      });
    },
  );
});
