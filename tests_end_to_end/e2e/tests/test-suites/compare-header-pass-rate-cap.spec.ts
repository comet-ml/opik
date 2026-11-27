import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * The single-experiment compare header's pass-rate badge, in the window where
 * rounding and the truth disagree (opik#9692 / OPIK-9304).
 *
 * **The defect.** `CompareExperimentsDetails` rendered
 * `Math.round(experiment.pass_rate * 100)` and nothing else. In JS
 * `Math.round(0.995 * 100)` is exactly 100, so a run that had FAILED an item
 * advertised "100% pass rate" — beside a red icon, which is the only hint a
 * reader got that the number was a lie. The PR swapped in the Experiments
 * table's capped formatter (`formatPassRatePercent`, which floors anything
 * below 1 to 99%).
 *
 * **Why nothing in the estate could see it.** The capability
 * `test-suites.pass-rate-display` has been `covered: true` for a long time, but
 * every pass rate the estate had ever rendered sat at 3/3 or 0/n. The
 * `0 < rate < 1` window is the only place a cap can do anything, and it was
 * reached by nothing — so the key read as covered while the behaviour under it
 * was untestable. That is the gap this spec closes; it adds a SURFACE and a
 * VECTOR to an existing key rather than claiming a new capability.
 *
 * **Why 199/200.** It is the smallest single run that reproduces the rounding:
 * `k/n >= 0.995` with `k < n` needs `n >= 200`. The premise is asserted rather
 * than trusted — the test states that plain rounding would read this run as
 * 100% before asserting that the badge does not — so the test cannot quietly
 * become a tautology if the stored rate ever changes.
 *
 * **Why the 2/2 control.** A build that capped EVERY rate at 99% would pass the
 * 199/200 assertion. The all-passing run beside it, which must read "100% pass
 * rate" with a GREEN icon, is what separates "capped the label" from "broke the
 * label", and the icon colour is what separates the label from the verdict.
 *
 * Both runs come from the `groupedPassRateExperiments` fixture, which already
 * seeded exactly these two for the group-row specs — this is a third surface
 * over the same seed, not a second seed. See that fixture for why the verdicts
 * are written to `PUT /v1/private/assertion-results` rather than judged by
 * `opik.run_tests` (an LLM judge cannot be steered to a chosen ratio), and for
 * the precondition check that refuses a seed these assertions could not fail
 * against.
 */
test.describe(
  'Test suites — compare header pass-rate badge',
  { tag: ['@t2-cuj', '@area:test-suites'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'a run one item short of perfect reads 99% pass rate, never 100%',
      { tag: ['@cap:test-suites.pass-rate-display'] },
      async ({ page, backendClient, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;
        const capped = seed.cap.experiments[0];

        await test.step('The server reports 199 of 200 passing', async () => {
          // Polled: `pass_rate_agg` is computed in ClickHouse off the assertion
          // rows, so the experiment read can briefly answer an earlier state.
          await expect
            .poll(() => backendClient.getExperimentPassRate(capped.id), {
              message: `the stored pass rate of ${capped.name}`,
              timeout: 60_000,
            })
            .toEqual({ passRate: 0.995, passedCount: 199, totalCount: 200 });
        });

        await test.step('Plain rounding would therefore read it as 100%', async () => {
          // The premise, stated rather than trusted. Without this the assertion
          // below would still pass on a build with no cap at all, if the stored
          // rate ever drifted out of the window where rounding goes wrong.
          expect(
            Math.round(0.995 * 100),
            'the arithmetic the badge used to do',
          ).toBe(100);
          expect(capped.expectedRowLabel, 'the label the cap produces').toBe('99%');
        });

        const compare = new CompareExperimentsPage(
          page,
          seed.projectId,
          seed.cap.datasetId,
          // Exactly one id: the badge is rendered only when the header is NOT
          // in comparison mode (`!isCompare`).
          [capped.id],
        );

        await test.step('The header badge reads exactly "99% pass rate", with a failing icon', async () => {
          await compare.gotoResults();
          await compare.expectPassRateBadge({ label: '99% pass rate', icon: 'failed' });
        });

        await test.step('And "100% pass rate" appears nowhere on the page', async () => {
          // Beside the equality above, not instead of it. `toHaveText` pins the
          // one badge; this pins the PAGE, so a second badge, a tooltip or a
          // summary line carrying the old wrong number fails too — and "100%"
          // is precisely what the defect rendered.
          await expect(
            page.getByText('100% pass rate', { exact: true }),
            'the pre-fix label anywhere on the compare page',
          ).toHaveCount(0);
        });
      },
    );

    test(
      'an all-passing run still reads 100% pass rate with a passing icon',
      { tag: ['@cap:test-suites.pass-rate-display'] },
      async ({ page, backendClient, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;
        const green = seed.green.experiments[0];

        await test.step('The server reports every item passing', async () => {
          await expect
            .poll(() => backendClient.getExperimentPassRate(green.id), {
              message: `the stored pass rate of ${green.name}`,
              timeout: 60_000,
            })
            .toEqual({ passRate: 1, passedCount: 2, totalCount: 2 });
        });

        const compare = new CompareExperimentsPage(
          page,
          seed.projectId,
          seed.green.datasetId,
          [green.id],
        );

        await test.step('The header badge reads "100% pass rate" with a passing icon', async () => {
          // The control that makes the 99% assertion mean something: a cap
          // applied unconditionally would fail here, so the two tests together
          // say the label follows the rate rather than a constant.
          await compare.gotoResults();
          await compare.expectPassRateBadge({ label: '100% pass rate', icon: 'passed' });
        });
      },
    );
  },
);
