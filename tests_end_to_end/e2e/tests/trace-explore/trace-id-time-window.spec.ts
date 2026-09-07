import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Time-windowed trace reads window on the week of the instant embedded in the
 * trace's UUIDv7 id (OPIK-7456).
 *
 * The week bound used to be taken with `toMonday(id_at)`, which returns a
 * 16-bit `Date`: an id whose week starts before 1970-01-01 underflowed and
 * wrapped to ~2149-06-06, so the row was dropped from every window it belonged
 * in and admitted to windows it did not. Nothing in the estate varied the id's
 * embedded timestamp — the smoke spec seeds present-day SDK ids and the one
 * other windowed read is a 24-hour window — so a wrapped bound left the suite
 * green.
 *
 * What this spec does and does not establish, stated plainly because a
 * windowing spec is easy to over-read.
 *
 * It asserts that time-windowed reads window on the id's own week, at id ages
 * that no other spec in the estate exercises. That is worth having: nothing
 * else varies the embedded timestamp, so a future change to the bound would
 * otherwise land unnoticed.
 *
 * It is NOT a regression gate for the wrap. On this schema `traces.id_at` is
 * `DateTime('UTC')`, so an out-of-range instant truncates before the week
 * expression sees it, and `toMonday` saturates rather than wrapping — checked
 * on ClickHouse 26.3: `toMonday(toDateTime('1970-01-01'))` is `1970-01-01`, not
 * a wrapped 2149 date. Old and new expressions therefore agree on every bound
 * reachable here, and no assertion below distinguishes them. This is the same
 * limitation the far-future half has (see the fixture), and it lifts when
 * `traces_local_v2` widens `id_at` to `DateTime64` — at which point these
 * assertions start discriminating without needing to be rewritten.
 *
 * The UI half is narrower still. Every Logs date range sends a `from_time` —
 * the default preset is `past30days`, and even `alltime` reaches only five
 * years back while the picker clamps to one year — which the read applies as
 * `id >= :uuid_from_time` ahead of the week predicate. An epoch-week id fails
 * that bound regardless, so the table step is a default-range smoke check: the
 * epoch row stays out of the view a user sees, and the panel step proves the
 * row existed to be excluded rather than never seeded.
 *
 * Deterministic despite naming a week: the ids are minted, not clocked, and
 * every bound below is either far outside any plausible run week or derived
 * from the run's own clock.
 */
test.describe('Trace id time window — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('a trace whose id predates the epoch is windowed on the week its id embeds', { tag: ['@cap:traces.list-traces'] }, async ({
    epochWindowTraces,
    project,
    backendClient,
    page,
  }) => {
    const { epoch, present } = epochWindowTraces;
    const seededIds = [epoch.id, present.id].sort();

    await test.step('Both traces are visible to an unwindowed read', async () => {
      const ids = await backendClient.listTraceIds({ projectId: project.id });
      // The project is fresh, so the whole answer is assertable: a read that
      // leaked another project's rows fails here rather than passing on a
      // find().
      expect(ids.sort()).toEqual(seededIds);
    });

    await test.step('A window ending tomorrow admits the epoch-week trace', async () => {
      const ids = await backendClient.listTraceIds({
        projectId: project.id,
        toTime: new Date(Date.now() + 24 * 60 * 60 * 1000),
      });
      // The epoch row's honest week (1969-12-29) is below this bound. Under the
      // wrap it read as ~2149-06-06 and was excluded from a window it belongs
      // in — with no error and no gap a reader could notice.
      expect(ids.sort()).toEqual(seededIds);
    });

    await test.step('A window ending in the epoch week admits only the epoch-week trace', async () => {
      const ids = await backendClient.listTraceIds({
        projectId: project.id,
        toTime: new Date('1970-01-02T00:00:00.000Z'),
      });
      // The complement of the assertion above, and the sharper one: the epoch
      // row is inside a window that a row wrapped to 2149 could never be in,
      // and the present-day row — which no honest reading admits — is out.
      expect(ids).toEqual([epoch.id]);
    });

    const logs = new LogsPage(page);

    await test.step('Smoke: the Logs table on its default range lists only the present-day trace', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();

      await expect(logs.traceRow(present.id)).toBeVisible();
      // Not a regression gate: the default range's `from_time` becomes an
      // `id >= :uuid_from_time` bound that excludes an epoch-week id whether or
      // not the week expression wraps. Asserted because it is the view a user
      // sees, not because it distinguishes the fix.
      await expect(logs.traceRow(epoch.id)).toHaveCount(0);
      await expect(logs.traceRows).toHaveCount(1);
      expect(await logs.countTraces()).toBe(1);
    });

    await test.step('The epoch-week trace is absent from that view but not from the project', async () => {
      const panel = await logs.openTraceById(epoch.id);
      await panel.waitForFullyLoaded();

      // Without this the step above would pass just as well if the seed had
      // never landed: the row exists and renders its payload, so its absence
      // from the table above is an exclusion rather than a missing seed.
      await expect(panel.traceNameInHeader(epoch.name)).toBeVisible();
      await expect(panel.inputValue(epoch.input)).toBeVisible();
      await expect(panel.outputValue(epoch.output)).toBeVisible();
    });
  });
});
