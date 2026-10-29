import { test, expect } from '@e2e/fixtures';

/**
 * What a trace's `source` survives, and who can see it (opik#8514).
 *
 * `source` is an `Enum8` column with `DEFAULT 'unknown'`, and until this release
 * an absent source was bound into it as a typed NULL. #8514 binds
 * `Source.UNKNOWN_VALUE` instead — the value the column default already
 * produces. The stated intent is that stored data is unchanged, and the risk it
 * creates is in the UPDATE path: an update is persisted as a partial insert that
 * merges over the stored row, and that merge keeps the old source only while the
 * incoming one is not `'unknown'`. An absent source now IS `'unknown'`, so an
 * ordinary partial update — one that carries an output and says nothing about
 * provenance, which is every update a real SDK sends — is newly capable of
 * downgrading a known `sdk` source.
 *
 * That downgrade is silent wrongness of the kind nothing else here would catch.
 * The trace stays listed, renders identically, and keeps every field the PATCH
 * did not carry. What changes is invisible: `Source.isLoggingSource` decides
 * which traces the online-evaluation samplers pick up, so a trace whose source
 * fell to `unknown` is scored by a different set of rules than the one it was
 * written under.
 *
 * #8514 added a merge test for `SpanDAO` and none for `TraceDAO`, and no spec in
 * the estate filters on `source` at all — this is both halves.
 *
 * API-level throughout, and tagged accordingly: `source` is not a column, a
 * filter chip or any other control in the UI, so there is no UI claim to make
 * here. Driving a browser to observe it would be slower, flakier and second-hand.
 */
test.describe('Trace source — API', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'a partial update that omits source does not downgrade a known source',
    { tag: ['@cap:traces.update-trace-api'] },
    async ({ traceSources, backendClient }) => {
      const patchedOutput = { answer: `${traceSources.sdk.name} patched answer` };

      await test.step('PATCH the sdk trace with a new output and no source', async () => {
        await backendClient.updateTraceOutput({
          traceId: traceSources.sdk.id,
          projectName: traceSources.projectName,
          output: patchedOutput,
        });
      });

      await test.step('The sdk trace kept its source, and the update did land', async () => {
        const payload = await backendClient.getTracePayload(traceSources.sdk.id);
        expect(payload, `trace ${traceSources.sdk.id} is still readable`).not.toBeNull();

        // The claim. Stated as the exact string rather than "not unknown", so a
        // merge that replaced `sdk` with a third value fails here too.
        expect(payload!.source, 'the stored source after a sourceless update').toBe('sdk');

        // The update landed — without this, "nothing changed" would satisfy the
        // assertion above just as well as a correct merge does, and the test
        // would be green against a PATCH the backend dropped on the floor.
        expect(payload!.output, 'the output the update carried').toEqual(patchedOutput);

        // ...and it was a MERGE, not a replace: the fields the PATCH said
        // nothing about are still there. This is the sibling failure mode, and
        // the one a trace panel would show.
        expect(payload!.name, 'the name the update did not carry').toBe(traceSources.sdk.name);
        expect(payload!.input, 'the input the update did not carry').toEqual(
          traceSources.sdk.input,
        );
      });

      await test.step('The bystanders are untouched', async () => {
        // The merge writes a whole row, so an update that resolved the wrong
        // target would rewrite a neighbour's source rather than its own — and
        // every assertion above would still pass.
        for (const bystander of [traceSources.legacy, traceSources.decoy]) {
          const payload = await backendClient.getTracePayload(bystander.id);
          expect(payload, `bystander ${bystander.name} is still readable`).not.toBeNull();
          expect(payload!.source, `source of bystander ${bystander.name}`).toBe(
            bystander.storedSource,
          );
          expect(payload!.output, `output of bystander ${bystander.name}`).toEqual(
            bystander.output,
          );
        }
      });
    },
  );

  test(
    'a source=sdk filter admits a trace written with no source and excludes other sources',
    { tag: ['@cap:traces.filter-traces'] },
    async ({ traceSources, backendClient }) => {
      await test.step('source = sdk returns the sdk trace AND the sourceless one', async () => {
        const ids = await backendClient.listTraceIds({
          projectId: traceSources.projectId,
          filters: [{ field: 'source', operator: '=', value: 'sdk' }],
        });

        // `TraceField.SOURCE` is `ENUM_LEGACY`, and `Source.legacyFallbackDbValue`
        // maps the filter value `sdk` — and only `sdk` — onto a second clause, so
        // the SQL is `(source = 'sdk' OR source = 'unknown')`. That fallback is
        // what keeps every row written before source tracking visible to the
        // filter a user reaches for, and it is the read-side counterpart of
        // #8514 binding `'unknown'` where a NULL used to go.
        //
        // The whole answer, sorted, rather than a lookup of the two ids: a read
        // that ALSO returned the playground trace is the failure this exists to
        // catch, and `find()`-ing each expected id would pass through it.
        expect(ids.slice().sort(), 'trace ids under a source=sdk filter').toEqual(
          [traceSources.sdk.id, traceSources.legacy.id].sort(),
        );
      });

      await test.step('source = playground returns only the playground trace', async () => {
        const ids = await backendClient.listTraceIds({
          projectId: traceSources.projectId,
          filters: [{ field: 'source', operator: '=', value: 'playground' }],
        });

        // The discriminator. Without it, "source = sdk returned two of my three
        // traces" is equally well explained by a filter that ignores `source`
        // and a project that happens to hold two matching rows. It is also the
        // other side of the legacy fallback: that clause is added for the value
        // `sdk` alone, so a `playground` filter must NOT sweep up the sourceless
        // trace.
        expect(ids, 'trace ids under a source=playground filter').toEqual([
          traceSources.decoy.id,
        ]);
      });

      await test.step('The project really does hold all three', async () => {
        // Proves the two filtered reads above were narrowing something. An
        // unfiltered read that returned two rows would mean the seed, not the
        // filter, produced the numbers this test just asserted.
        const ids = await backendClient.listTraceIds({ projectId: traceSources.projectId });
        expect(ids.slice().sort(), 'every trace in the project, unfiltered').toEqual(
          traceSources.all.map((t) => t.id).sort(),
        );
      });
    },
  );
});
