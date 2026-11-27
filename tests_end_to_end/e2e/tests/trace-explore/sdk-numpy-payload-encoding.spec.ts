import {
  test,
  expect,
  NUMPY_EXPECTED_PAYLOAD,
  NUMPY_NUMERIC_KEY,
  NUMPY_NUMERIC_VALUE,
} from '@e2e/fixtures';
import type { BackendFilter } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * opik#9573: the Python SDK logs numpy scalars as JSON numbers and booleans
 * instead of strings.
 *
 * `jsonable_encoder.encode` is on the write path of EVERY SDK-logged trace —
 * `encoder_helpers.encode_and_anonymize` runs it over the whole trace and span
 * write kwargs — so the 53 specs that drive the Python SDK exercise it
 * constantly for ordinary types. `numpy` appeared nowhere in
 * `tests_end_to_end`, so the branch this release added was guarded only by the
 * SDK's own unit tests.
 *
 * Both of its failure directions are silent, which is why an assertion on
 * presence alone would be worthless here:
 *
 *  - **Wrong types.** `np.int64(7)` falling through to `str(obj)` stores the
 *    JSON string `"7"` where a number belongs. Nothing errors; the value is
 *    simply the wrong type for every chart and numeric filter downstream.
 *  - **A dropped batch.** An exception inside the encoder loses the batch
 *    WITHOUT raising to the caller, so "nothing was logged" is a real outcome
 *    and one the SDK reports to nobody.
 *
 * So the spec's load-bearing assertion is the plain-Python CONTROL: a second
 * trace carrying the equivalent native values, logged in the same request and
 * flushed with the same call. The numpy trace's stored payload has to be
 * identical to it, field by field and type by type. Asserting the numpy trace
 * alone against a literal would pin whatever this release happens to produce;
 * asserting it against a control that went through the same path is what makes
 * a string-instead-of-number a failure rather than a different-looking pass.
 * And a flush that silently dropped a batch takes both traces, so the control
 * is also what stops "the numpy trace is missing" being read as a numpy bug.
 *
 * Surface: `both`. The value is written through the SDK and read back through
 * the REST API *and* off the trace panel, because the panel is where a user
 * sees it: a quoted `"7"` is as visible in the Details tab's Metadata block as
 * it is in the payload, and the two could disagree.
 */
test.describe(
  'Trace explore — numpy scalars logged through the Python SDK',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    /**
     * A metadata filter on the numeric key, as the REST layer serialises it.
     *
     * `type: 'dictionary'` with a `key` is how every metadata filter in the
     * estate is shaped (see `span-quick-filter-routing.spec.ts`); the backend
     * compiles `>` on it to
     * `toFloat64OrNull(JSON_VALUE(metadata, '$.i64')) > toFloat64OrNull('6')`.
     */
    const metadataFilter = (operator: string, value: number): BackendFilter => ({
      field: 'metadata',
      type: 'dictionary',
      key: NUMPY_NUMERIC_KEY,
      operator,
      value: String(value),
    });

    test(
      'the stored payload matches a plain-Python control exactly, and the panel renders numbers and booleans unquoted',
      { tag: ['@cap:traces.details-tab'] },
      async ({ numpyPayloadTraces, backendClient, page }) => {
        const {
          projectId,
          numpyTraceId,
          numpyTraceName,
          controlTraceId,
          controlTraceName,
          expected,
          numpyVersion,
        } = numpyPayloadTraces;

        await test.step(`numpy ${numpyVersion} built the payload`, async () => {
          // Attached, never asserted: the conversion is a property of
          // `np.bool_` / `np.integer` / `np.floating` as types rather than of
          // any numpy release. It is here because a failure below is far
          // cheaper to read with the version in hand.
          test.info().annotations.push({ type: 'numpy', description: numpyVersion });
        });

        await test.step('The bridge built its control from the payload this spec expects', async () => {
          // The bridge returns the dict it logged; the spec holds its own copy.
          // Comparing them is what stops `routes/numpy_payloads.py` quietly
          // redefining the expectation every assertion below rests on.
          expect(
            expected,
            'the bridge control payload and the spec literal must be the same shape',
          ).toEqual(NUMPY_EXPECTED_PAYLOAD);
        });

        const [numpyTrace, controlTrace] = await test.step(
          'Both traces arrived, with every field the encoder touches intact',
          async () => {
            const numpy = await backendClient.getTracePayload(numpyTraceId);
            const control = await backendClient.getTracePayload(controlTraceId);
            // A dropped batch is not a missing field: the row lands with ids
            // and timestamps and NOTHING else. Asserting each field is present
            // is therefore the check for it, and it has to come before any
            // comparison — two absent metadata blobs compare equal.
            for (const [label, trace, name] of [
              ['the numpy trace', numpy, numpyTraceName],
              ['the control trace', control, controlTraceName],
            ] as const) {
              expect(trace, `${label} is readable by id`).not.toBeNull();
              expect(trace!.name, `${label} kept its name`).toBe(name);
              expect(trace!.input, `${label} kept its input`).not.toBeNull();
              expect(trace!.output, `${label} kept its output`).not.toBeNull();
              expect(trace!.metadata, `${label} kept its metadata`).not.toBeNull();
            }
            return [numpy!, control!] as const;
          },
        );

        await test.step("The numpy trace's metadata is identical to the control's", async () => {
          expect(
            numpyTrace.metadata,
            'the numpy payload and the plain-Python payload stored the same JSON',
          ).toEqual(controlTrace.metadata);
          expect(
            numpyTrace.metadata,
            'and that JSON is the expected shape, so neither is wrong in the same way',
          ).toEqual(NUMPY_EXPECTED_PAYLOAD);
        });

        await test.step('Each converted value is stored as the JSON type it should be', async () => {
          // `toEqual` above compares 7 to 7 — but it would also be satisfied if
          // both sides were the string "7", which is the pre-fix behaviour the
          // control cannot rule out on its own if the control somehow regressed
          // too. These assertions name the type directly, so there is no shape
          // of the encoder that passes them while storing strings.
          const metadata = numpyTrace.metadata as Record<string, unknown>;
          expect(typeof metadata.i64, 'np.int64 is a JSON number, not a string').toBe('number');
          expect(typeof metadata.f32, 'np.float32 is a JSON number, not a string').toBe('number');
          expect(typeof metadata.b, 'np.bool_ is a JSON boolean, not the string "True"').toBe(
            'boolean',
          );
          // The two the fix deliberately leaves alone. They are as much part of
          // the contract as the conversions: `.item()` on a datetime64 returns
          // a raw integer, and a timedelta64 turned into one loses its unit.
          expect(typeof metadata.dt64, 'np.datetime64 stays readable text').toBe('string');
          expect(typeof metadata.td64, 'np.timedelta64 stays text, with its unit').toBe('string');
          expect(Array.isArray(metadata.arr), 'an ndarray is a JSON list').toBe(true);
        });

        await test.step("The trace's input and output carry the same converted payload", async () => {
          // Metadata, input and output are separate keys of the same write
          // kwargs dict. The encoder walks the whole dict, so a branch that
          // reached only one of them would still be a regression — and the
          // panel renders all three.
          expect(
            numpyTrace.input,
            "the tracked function's argument was encoded the same way",
          ).toEqual({ payload: NUMPY_EXPECTED_PAYLOAD });
          expect(numpyTrace.output, 'and so was its return value').toEqual(
            NUMPY_EXPECTED_PAYLOAD,
          );
        });

        await test.step('The trace panel renders the numbers and the boolean unquoted', async () => {
          const logs = new LogsPage(page);
          await logs.goto(projectId);
          await logs.waitForReady();
          const panel = await logs.openTraceById(numpyTraceId);
          await panel.waitForFullyLoaded();

          // The Metadata block is a CodeMirror editor, so each leaf renders as
          // one `.cm-line`. The regexes below accept the viewer's YAML form
          // (`i64: 7`) and its JSON form (`"i64": 7,`) but NOT a quoted value —
          // which is the whole point: `i64: "7"` is what a user saw before this
          // fix, and it has to fail here.
          const METADATA = 'Metadata';
          const unquoted: Array<[string, RegExp, string]> = [
            ['i64', /^\s*"?i64"?:\s*7,?\s*$/, '7, not "7"'],
            ['f32', /^\s*"?f32"?:\s*1\.5,?\s*$/, '1.5, not "1.5"'],
            ['b', /^\s*"?b"?:\s*true,?\s*$/, 'true, not "True"'],
          ];
          for (const [key, pattern, what] of unquoted) {
            await expect(
              panel.attributeLine(METADATA, key),
              `the ${key} line is rendered exactly once`,
            ).toHaveCount(1);
            await expect(
              panel.attributeLine(METADATA, key),
              `and it reads ${what}`,
            ).toHaveText(pattern);
          }

          // The timedelta's unit is the readable half of "stays as text", and
          // the one a user would notice going missing: 5 as a bare number says
          // nothing about seconds.
          await expect(
            panel.attributeLine(METADATA, 'td64'),
            'the timedelta renders with its unit',
          ).toContainText(String(NUMPY_EXPECTED_PAYLOAD.td64));
        });

        await test.step('A numeric metadata filter reaches the value the numpy payload wrote', async () => {
          // What the release report asked to confirm by hand: the key carrying
          // `np.int64` is reachable by a NUMERIC comparison. This corroborates
          // the fix from the user's side rather than discriminating it on its
          // own — `JSON_VALUE` unwraps a JSON string, so `toFloat64OrNull`
          // would also have parsed the pre-fix `"7"`. The byte-exact comparison
          // against the control above is what makes the fix itself assertable.
          //
          // Asserted by EXHAUSTION — `total` and the whole id set, not just
          // "mine is in there". The project holds exactly these two traces and
          // both carry the same payload, so each matching filter must return
          // both ids and each non-matching one must return nothing. A filter
          // that leaked an unrelated row, or that matched on a substring, fails
          // here rather than passing on a `find()`.
          const bothIds = [numpyTraceId, controlTraceId].sort();

          const unfiltered = await backendClient.searchTraceIdsPage({ projectId });
          expect(
            unfiltered.total,
            'the project holds exactly the numpy trace and its control',
          ).toBe(2);
          expect(unfiltered.ids.slice().sort(), 'and they are the two seeded ids').toEqual(
            bothIds,
          );

          for (const [operator, value] of [
            ['>', NUMPY_NUMERIC_VALUE - 1],
            ['=', NUMPY_NUMERIC_VALUE],
          ] as const) {
            const page_ = await backendClient.searchTraceIdsPage({
              projectId,
              filters: [metadataFilter(operator, value)],
            });
            expect(
              page_.total,
              `${NUMPY_NUMERIC_KEY} ${operator} ${value} matches both traces`,
            ).toBe(2);
            expect(
              page_.ids.slice().sort(),
              `and returns exactly their ids for ${operator} ${value}`,
            ).toEqual(bothIds);
          }

          for (const [operator, value] of [
            ['>', NUMPY_NUMERIC_VALUE],
            ['=', NUMPY_NUMERIC_VALUE + 1],
          ] as const) {
            const page_ = await backendClient.searchTraceIdsPage({
              projectId,
              filters: [metadataFilter(operator, value)],
            });
            expect(
              page_.total,
              `${NUMPY_NUMERIC_KEY} ${operator} ${value} matches neither trace`,
            ).toBe(0);
            expect(page_.ids, `and returns no ids for ${operator} ${value}`).toEqual([]);
          }
        });
      },
    );
  },
);
