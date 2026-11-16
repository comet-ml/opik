import {
  test,
  expect,
  SUITE_RUNS_PER_ITEM,
  SUITE_PASS_THRESHOLD,
  OVERRIDE_RUNS_PER_ITEM,
} from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { TestSuiteItemsPage } from '@e2e/pom/test-suite-items.page';

/**
 * "Add to test suite" shows the SUITE's pass criteria and adds items that
 * inherit them, instead of resetting every item to 1 of 1 (opik#9482,
 * OPIK-8656).
 *
 * `AddToTestSuiteDialog` seeds its Evaluation criteria section from
 * `DEFAULT_EXECUTION_POLICY` — 1 run, threshold 1 — and sent that as an
 * `execution_policy` on every add. So a trace added to a suite that runs each
 * item five times and needs three passes was stored with a 1-of-1 override of
 * its own: it ran once, a single flake failed it, and it no longer tracked the
 * suite if the suite's criteria were later changed. 9482 reads the suite's
 * latest version instead (`useDatasetVersionsList`, page 1 size 1) and sends an
 * `execution_policy` only when the user actually changed it:
 *
 * ```ts
 * const policyOverride =
 *   customPolicy && !isEqual(customPolicy, suiteExecutionPolicy) ? customPolicy : null;
 * ```
 *
 * ## Why the failure is worth a test rather than a glance
 *
 * It is SILENT and it inverts a result. A suite asking for 3 of 5 is asking for
 * tolerance to a flaky run; an item quietly held to 1 of 1 fails on the first
 * bad roll, and the suite reports a failure the criteria said to forgive.
 * Nothing in the UI says the item is on different terms — the Pass criteria
 * cell renders "1 of 1 must pass" the same way it renders an inherited value.
 *
 * ## Why this spec exists beside the field-mappings one
 *
 * `traces.add-trace-to-dataset` reads covered, but
 * `dataset-item-field-mappings.spec.ts` drives the DATASET dialog and says in
 * as many words that it skips the test-suite one. So `AddToTestSuiteDialog` —
 * the only dialog with an Evaluation criteria section at all — is driven by no
 * spec, and the three lines above could be reverted without failing anything.
 *
 * ## Surface: both, in both directions
 *
 * The suite is seeded through the SDK, read back through the dialog, written
 * through the dialog, and read again through the API and the suite items page.
 * Asserting on the REQUEST the dialog sends is the load-bearing part: "the item
 * inherits" is stored as an ABSENT policy, and a server that defaulted an
 * absent policy to the suite's would make a stored-state-only test pass over a
 * dialog still sending 1 of 1. The stored state is asserted too, because the
 * request alone would not catch a backend that dropped what it was sent.
 */

/** What the Pass criteria cell reads for a given policy — `formatExecutionPolicy`. */
const criteriaLabel = (threshold: number, runs: number): string =>
  `${threshold} of ${runs} must pass`;

test.describe(
  'Logs — Add to test suite inherits the suite pass criteria',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'the dialog offers the suite criteria, adds an inheriting item, and stores an override only when one is typed',
      { tag: ['@cap:traces.add-trace-to-dataset'] },
      async ({ project, passCriteriaSuite, backendClient, page }) => {
        const logs = new LogsPage(page);
        const [firstTrace, secondTrace] = passCriteriaSuite.traceIds;

        await test.step('The suite really holds a 3-of-5 policy and no items', async () => {
          // Both halves of the precondition, through the API, before the
          // browser opens. A suite that had stored 1-of-1 would make the dialog
          // show 1 and 1 CORRECTLY, and the test below would report the
          // regression over a bad seed; a suite with items of its own would put
          // rows beside the two this test adds.
          expect(
            await backendClient.suiteVersionPolicy(passCriteriaSuite.suiteId),
            'the suite\'s latest version carries the pass criteria the dialog must read',
          ).toEqual({ runsPerItem: SUITE_RUNS_PER_ITEM, passThreshold: SUITE_PASS_THRESHOLD });
          expect(
            await backendClient.listSuiteItemPolicies(passCriteriaSuite.suiteId),
            'the suite starts empty, so every row on the items page is one this test added',
          ).toEqual([]);
        });

        await test.step('Open Logs with both traces listed', async () => {
          await logs.goto(project.id);
          await logs.waitForReady();
          for (const traceId of passCriteriaSuite.traceIds) {
            await expect(
              logs.traceRow(traceId),
              'the seeded trace is listed, so there is something to add',
            ).toHaveCount(1);
          }
        });

        const inherited = await test.step(
          'Add the first trace without touching the criteria',
          async () => {
            await logs.selectTrace(firstTrace);
            const dialog = await logs.openAddToTestSuite();
            await dialog.selectSuite(passCriteriaSuite.suiteName);

            expect(
              await dialog.readCriteria(),
              `the dialog offers the SUITE's criteria — ${SUITE_PASS_THRESHOLD} of ` +
                `${SUITE_RUNS_PER_ITEM}, not the 1-of-1 default it used to reset to`,
            ).toEqual({
              runsPerItem: String(SUITE_RUNS_PER_ITEM),
              passThreshold: String(SUITE_PASS_THRESHOLD),
            });

            return dialog.submit();
          },
        );

        await test.step('That add carried no execution_policy at all', async () => {
          expect(
            inherited.traceIds,
            'the request is about the trace that was ticked',
          ).toEqual([firstTrace]);
          // The claim in its exact form. A policy EQUAL to the suite's would
          // also produce the right numbers on screen today while storing an
          // override that stops tracking the suite the moment it is edited —
          // so "no key" is the assertion, not "the right numbers".
          expect(
            inherited.executionPolicy,
            'an untouched dialog sends no execution_policy, so the item inherits the suite\'s',
          ).toBeNull();
        });

        const overridden = await test.step(
          'Add the second trace with runs lowered to 2',
          async () => {
            // A fresh page rather than reusing the first dialog's state: the
            // selection and the dialog are both reset by the add, and the
            // second half of this test is about a SECOND visit to the dialog
            // reading the suite afresh.
            await page.reload();
            await logs.waitForReady();
            await logs.selectTrace(secondTrace);
            const dialog = await logs.openAddToTestSuite();
            await dialog.selectSuite(passCriteriaSuite.suiteName);
            await dialog.setRunsPerItem(OVERRIDE_RUNS_PER_ITEM);

            expect(
              await dialog.readCriteria(),
              `lowering runs to ${OVERRIDE_RUNS_PER_ITEM} drags the threshold down with it — ` +
                `the threshold's max is the runs value, and ${SUITE_PASS_THRESHOLD} of ` +
                `${OVERRIDE_RUNS_PER_ITEM} is not satisfiable`,
            ).toEqual({
              runsPerItem: String(OVERRIDE_RUNS_PER_ITEM),
              passThreshold: String(OVERRIDE_RUNS_PER_ITEM),
            });

            return dialog.submit();
          },
        );

        await test.step('That add carried the typed policy', async () => {
          expect(overridden.traceIds, 'the request is about the second trace').toEqual([
            secondTrace,
          ]);
          expect(
            overridden.executionPolicy,
            'a criteria the user changed IS sent — the dialog distinguishes "left alone" from ' +
              '"set to something", which is the whole mechanism',
          ).toEqual({
            runs_per_item: OVERRIDE_RUNS_PER_ITEM,
            pass_threshold: OVERRIDE_RUNS_PER_ITEM,
          });
        });

        const items = await test.step('The suite stored one inheriting item and one override', async () => {
          // Server-side, and the whole collection: two items, one with a null
          // policy and one with its own. A read that only looked for the
          // override would pass against a build that had written a 1-of-1
          // policy onto the first item as well.
          //
          // Polled, because `from-traces` answers before the items are
          // queryable — the write goes through the ingestion path, so an
          // immediate read routinely sees one item. Polling the COUNT only, so
          // the policy assertions below still fail hard rather than being
          // retried into agreement.
          await expect
            .poll(
              async () =>
                (await backendClient.listSuiteItemPolicies(passCriteriaSuite.suiteId)).length,
              {
                message: 'both adds become queryable — dataset item writes are not immediate',
                timeout: 30_000,
              },
            )
            .toBe(2);
          const stored = await backendClient.listSuiteItemPolicies(passCriteriaSuite.suiteId);
          expect(stored, 'exactly the two items this test added').toHaveLength(2);
          const policies = stored.map((item) => item.executionPolicy);
          expect(
            policies.filter((policy) => policy === null),
            'exactly one item carries NO policy of its own, and so inherits the suite\'s',
          ).toHaveLength(1);
          expect(
            policies.filter((policy) => policy !== null),
            'and exactly one carries the typed override',
          ).toEqual([
            { runsPerItem: OVERRIDE_RUNS_PER_ITEM, passThreshold: OVERRIDE_RUNS_PER_ITEM },
          ]);
          return stored;
        });

        await test.step('The items page shows the inherited criteria and the override', async () => {
          // The user-visible end of it, which is where the silence was: an item
          // held to different terms from its suite looks identical to one
          // tracking them. Each cell is addressed by its own item id, so the
          // two rows cannot be confused for one another.
          const suiteItems = new TestSuiteItemsPage(page, project.id, passCriteriaSuite.suiteId);
          await suiteItems.goto();
          await suiteItems.waitForReady();

          const inheritingId = items.find((item) => item.executionPolicy === null)!.id;
          const overriddenId = items.find((item) => item.executionPolicy !== null)!.id;

          await expect(
            suiteItems.executionPolicyCell(inheritingId),
            'the inheriting item shows the suite\'s criteria',
          ).toHaveText(criteriaLabel(SUITE_PASS_THRESHOLD, SUITE_RUNS_PER_ITEM));
          await expect(
            suiteItems.executionPolicyCell(overriddenId),
            'and the overridden item shows its own',
          ).toHaveText(criteriaLabel(OVERRIDE_RUNS_PER_ITEM, OVERRIDE_RUNS_PER_ITEM));
        });
      },
    );
  },
);
