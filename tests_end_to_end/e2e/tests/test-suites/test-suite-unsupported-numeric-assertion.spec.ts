import { test, expect } from '@e2e/fixtures';

/**
 * A suite whose evaluator schema holds an unsupported NUMERIC item still
 * produces a usable run (opik#8557 · OPIK-8401).
 *
 * `test-suites.run-suite-sdk` is recorded covered, but its note is "3/3 pass
 * experiment" — the BOOLEAN path, with every assertion judged by a real model.
 * 8557 added a branch beside it: an `INTEGER` or `DOUBLE` schema item is a type
 * `LLMJudge` cannot score, and instead of erroring the run through it now
 * returns an explicit failed result per item, carrying the type it could not
 * handle and a reason naming the assertion. Before it, the branch did not exist.
 *
 * That branch lives in no e2e test, and it is worth one for a reason the rest of
 * this area cannot claim: a numeric-ONLY schema short-circuits the model
 * entirely. `LLMJudge.score` sees no supported assertion and returns
 * `_merge_config_schema_results([])` before `_agenerate_and_parse` is reached,
 * so this is that rare thing — an LLM-judge test with no provider call, hence
 * no key, no cost and no stochasticity, while still driving the real
 * `opik.run_tests` path against the real backend.
 *
 * The failure mode is what makes it worth asserting rather than assuming. The
 * alternative to "a failed result" is not "an error a user investigates": it is
 * a run that completes reporting a pass rate computed over an assertion that
 * never ran, or one that raises mid-suite and loses the items it had already
 * scored. Both look like ordinary outcomes.
 *
 * API/SDK-level by design, and the `@cap:` is `run-suite-sdk` for the same
 * reason: the contract 8557 shipped is the shape of the result the SDK returns,
 * and nothing about it is a UI control. The exploration that proposed this spec
 * filed separately — and did NOT propose as a spec — that the failed result
 * never reaches the backend, so the experiment carries no feedback score for
 * it and the run's item view shows nothing. That filter is pre-existing, 8557
 * did not touch it, and a spec pinning either behaviour would be guessing at a
 * product decision; so this asserts the SDK contract the PR changed and claims
 * nothing about the UI.
 */
test.describe(
  'Test Suites — an evaluator schema item whose type the judge cannot score',
  { tag: ['@t2-cuj', '@area:test-suites'] },
  () => {
    /** Two SDK writes, a round-trip read, then a real suite run. */
    test.setTimeout(180_000);

    test(
      'a numeric-only schema runs to completion and reports an explicit failed assertion per item',
      { tag: ['@cap:test-suites.run-suite-sdk'] },
      async ({ numericAssertionSuite, sdkClient, project }) => {
        const suite = numericAssertionSuite;

        await test.step('The typed schema survived the round trip through the backend', async () => {
          // The fixture already asserted this against the write it made; stated
          // again here because every assertion below is only about the numeric
          // branch if the suite the run reads really carries an INTEGER item. A
          // schema that had silently fallen back to BOOLEAN would send the run
          // to a model, and the failure would read as a missing provider key
          // rather than as a write that did not land.
          expect(
            suite.schemaItems,
            'the suite carries exactly the one INTEGER item, as the backend answers it',
          ).toEqual([
            {
              name: suite.item.name,
              type: 'INTEGER',
              description: suite.item.description,
            },
          ]);
          expect(
            suite.globalAssertions,
            'and get_global_assertions() reports it rather than hiding an item it cannot score',
          ).toEqual([suite.item.description]);
        });

        const result = await test.step('Run the suite with no judge model and no provider key', async () => {
          // `judge_model` deliberately omitted. It is not a convenience: a model
          // named here would be the one thing that could make a provider call
          // possible, and the claim this step rests on is that none was made.
          // The bridge passes no `model` to `opik.run_tests`, so
          // `dataset.get_evaluators(None)` builds the judge with no model at
          // all — and on a numeric-only schema that judge never reaches for one.
          //
          // No `test.skip` on a provider key either, unlike `test-suites-smoke`:
          // this run is supposed to work WITHOUT one, so skipping when the key
          // is absent would skip exactly the environment the spec is for.
          return sdkClient.python.runTestSuite({
            suite_name: suite.name,
            project_name: project.name,
            task_output: 'a plain answer with nothing numeric about it',
            experiment_name: `${suite.name}-numeric-run`,
            include_assertions: true,
          });
        });

        await test.step('The run completed, covering every item and passing none', async () => {
          // "Completed" is the first half of the contract and the easy half to
          // skip over: the pre-8557 alternative to a failed result was the run
          // raising, which from outside looks like a broken suite rather than an
          // unscorable assertion.
          expect(
            result.items_total,
            'the run reports every test case in the suite',
          ).toBe(suite.itemCount);
          // The id SET as well as the count: a run that scored one item twice
          // and another never still reports the right total.
          expect(
            result.item_ids.length,
            'and produced a result for that many distinct items',
          ).toBe(suite.itemCount);
          expect(
            new Set(result.item_ids).size,
            'each item appearing once, so no item was covered twice in place of another',
          ).toBe(suite.itemCount);
          expect(
            result.items_passed,
            'and none passed — an assertion that could not be scored must not count as met',
          ).toBe(0);
          expect(
            result.pass_rate,
            'so the pass rate is 0 over items that did have an assertion, not null',
          ).toBe(0);
          expect(
            result.experiment_id,
            'and the run still created an experiment, as any other suite run does',
          ).not.toBeNull();
        });

        // `include_assertions` was asked for, so `null` would mean the bridge
        // did not answer rather than "there were none" — asserted away before
        // anything is read out of it.
        expect(
          result.item_assertions,
          'the run answered its assertion results, as the request asked',
        ).not.toBeNull();
        const assertions = result.item_assertions!;

        await test.step('Each item carries the explicit failed assertion 8557 added', async () => {
          expect(
            assertions.length,
            'exactly one result per item: one schema item, one run per item',
          ).toBe(suite.itemCount);

          for (const assertion of assertions) {
            expect(
              assertion.scoring_failed,
              `item ${assertion.dataset_item_id}: the result is marked as a scoring FAILURE, ` +
                'which is what keeps it out of the pass tally',
            ).toBe(true);
            expect(
              assertion.value,
              `item ${assertion.dataset_item_id}: and carries 0.0 rather than an absent value`,
            ).toBe(0);
            // The name is the item's DESCRIPTION while the reason names the
            // item's NAME — the two differ in the seed on purpose, so a build
            // that had swapped them fails here instead of passing.
            expect(
              assertion.name,
              `item ${assertion.dataset_item_id}: the result is named for the schema item's ` +
                'description, which is what a reader sees beside it',
            ).toBe(suite.item.description);
            expect(
              assertion.metadata,
              `item ${assertion.dataset_item_id}: the result carries metadata`,
            ).not.toBeNull();
            expect(
              assertion.metadata!.unsupported_type,
              `item ${assertion.dataset_item_id}: naming the type that could not be scored, ` +
                'so a consumer can tell this apart from an assertion the model simply failed',
            ).toBe('INTEGER');
            expect(
              assertion.reason,
              `item ${assertion.dataset_item_id}: and a reason`,
            ).not.toBeNull();
            // Both halves of the sentence, not a loose substring: the reason has
            // to say WHICH assertion and WHY, and a reason that named the item
            // without saying what is supported leaves a user with nothing to do
            // about it.
            expect(
              assertion.reason!,
              `item ${assertion.dataset_item_id}: the reason names the item and its type`,
            ).toContain(`${suite.item.name} (INTEGER)`);
            expect(
              assertion.reason!,
              `item ${assertion.dataset_item_id}: and says what IS supported`,
            ).toContain('only BOOLEAN');
            expect(
              assertion.category_name,
              `item ${assertion.dataset_item_id}: filed as a suite assertion, not as a ` +
                'free-standing metric score',
            ).toBe('suite_assertion');
          }
        });

        await test.step('The failure came from the short-circuit, not from a provider that refused', async () => {
          // This is the "no provider call was made" claim, asserted rather than
          // timed. A wall-clock bound would be the obvious proxy and a bad one:
          // it would flake on a loaded shared workspace and it would not
          // actually distinguish the two outcomes.
          //
          // What does distinguish them is the reason text. A judged assertion
          // against an environment with no usable key ALSO comes back failed —
          // the engine runs this entrypoint with
          // `ErrorTolerance.METRIC_ERRORS`, so a metric that raises becomes a
          // failed score carrying the exception — but its reason is the
          // provider's authentication error. The sentence asserted above is
          // built in exactly one place, `_merge_config_schema_results` on the
          // path where no supported assertion exists, and `unsupported_type`
          // metadata is attached nowhere else. So the presence of one and the
          // absence of the other together say the model was never consulted.
          for (const assertion of assertions) {
            expect(
              assertion.reason!,
              `item ${assertion.dataset_item_id}: the reason is the SDK's own sentence about ` +
                'an unsupported type, not a provider rejecting the request',
            ).not.toMatch(/authentic|api[_\- ]?key|unauthor|\b401\b|rate limit/i);
          }
          // Reported for the trace, and the only thing worth asserting about it
          // is that the SDK filled it in: the number itself is the shared
          // environment's latency, not a property of this branch.
          expect(result.total_time, 'the run reports how long it took').not.toBeNull();
        });
      },
    );
  },
);
