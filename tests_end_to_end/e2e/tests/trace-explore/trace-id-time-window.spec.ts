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
 * Both surfaces on purpose. The API half is the bound itself, read at exactly
 * the ages that discriminate. The UI half is the symptom a user would have
 * seen: an epoch-week trace surfacing in a "last 30 days" Logs view, which is
 * the latent false positive the fix removes.
 *
 * Deterministic despite naming a week: the ids are minted, not clocked, and
 * every bound below is either far outside any plausible run week or derived
 * from the run's own clock.
 */
test.describe('Trace id time window — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('a trace whose id predates the epoch is windowed on its real week, not a wrapped one', { tag: ['@cap:traces.list-traces'] }, async ({
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

    await test.step('The Logs table on its default range lists only the present-day trace', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();

      await expect(logs.traceRow(present.id)).toBeVisible();
      // The false positive the fix removes: wrapped to 2149-06-06, the epoch
      // row satisfied a "past 30 days" lower bound and rendered here.
      await expect(logs.traceRow(epoch.id)).toHaveCount(0);
      await expect(logs.traceRows).toHaveCount(1);
      expect(await logs.countTraces()).toBe(1);
    });

    await test.step('The epoch-week trace is absent from that view but not from the project', async () => {
      const panel = await logs.openTraceById(epoch.id);
      await panel.waitForFullyLoaded();

      // Without this the step above would pass just as well if the seed had
      // never landed: the row exists and renders its payload, and only the
      // window excludes it.
      await expect(panel.traceNameInHeader(epoch.name)).toBeVisible();
      await expect(panel.inputValue(epoch.input)).toBeVisible();
      await expect(panel.outputValue(epoch.output)).toBeVisible();
    });
  });
});
