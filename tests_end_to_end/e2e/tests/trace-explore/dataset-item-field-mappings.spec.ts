import {
  test,
  expect,
  FM_TRACE_INPUT,
  FM_TRACE_OUTPUT,
  FM_TRACE_METADATA,
  FM_SPAN_INPUT,
  FM_SPAN_OUTPUT,
  FM_SPAN_MODEL,
} from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendClient, DatasetItemRef, TraceEnrichment } from '@e2e/core/backend';

/**
 * `field_mappings` on create-dataset-items-from-{traces,spans} (OPIK-7791).
 *
 * The endpoints have always written a fixed enriched shape — `input`,
 * `expected_output`, and whichever extras `enrichment_options` asks for. This
 * release lets a caller name the dataset-item field and the path into the
 * trace or span it reads from, and the endpoint's own `@Schema` makes three
 * claims about it: a mapping reads from a path, it takes precedence over the
 * field enrichment would have produced, and it is ignored entirely for a test
 * suite.
 *
 * All three are silent-wrongness risks of the same kind. A mapping that
 * resolved the wrong path — or was quietly dropped — writes a dataset item
 * that looks completely ordinary: the right number of rows, plausible-looking
 * columns, no error anywhere. Every experiment then run off that dataset
 * evaluates the wrong field, and nothing says so.
 *
 * Mostly API-level, deliberately: the mapping is resolved server-side by
 * `FieldMappingResolver`, and each claim is an exact string in the created
 * item's `data`. The last test is the other half — it drives the Advanced
 * mapping section of the Add-to-dataset dialog, which is the only way a user
 * ever sets one of these, and reads the result back through the API. That
 * crossing is the point: it is what catches the dialog and the endpoint
 * disagreeing about what was asked for.
 *
 * Every key set below is compared **exactly**, not searched. Asserting only
 * that the mapped field is present would pass just as well for an endpoint
 * that also wrote the field the mapping was supposed to replace.
 */

/** Everything enrichment can add, so the unmapped fields are visibly untouched. */
const ALL_TRACE_ENRICHMENT: TraceEnrichment = {
  includeSpans: true,
  includeTags: true,
  includeFeedbackScores: true,
  includeComments: true,
  includeUsage: true,
  includeMetadata: true,
};

/**
 * Nothing but the always-present `input`/`expected_output`, for the span half.
 *
 * The span carries no tags, scores, comments, usage or metadata anyway — this
 * states that rather than relying on it, so the expected key set is decided
 * here and not by what the seed happened to omit.
 */
const NO_SPAN_ENRICHMENT = {
  includeTags: false,
  includeFeedbackScores: false,
  includeComments: false,
  includeUsage: false,
  includeMetadata: false,
};

/**
 * The four mappings the trace-side tests send, one per documented behaviour:
 *
 *  - `my_field` — a brand-new field read from a path inside `input`.
 *  - `input` — collides with a field enrichment produces, and must win it.
 *  - `from_metadata` — reads out of a section enrichment writes wholesale,
 *    proving a mapping is not restricted to `input`/`output`.
 *  - `bogus` — resolves to nothing, and must be absent rather than null.
 */
const TRACE_FIELD_MAPPINGS = {
  my_field: 'input.input_text',
  input: 'output.answer',
  from_metadata: 'metadata.mkey',
  bogus: 'input.does_not_exist',
};

/**
 * The dataset's items once the endpoint has answered, asserted to be exactly
 * one.
 *
 * The fixture's datasets start empty, so "one item" is the whole answer rather
 * than a row found among others — an endpoint that wrote the mapped item AND
 * an unmapped duplicate fails here instead of at a lookup that would have
 * found the right one either way.
 */
async function readSoleItem(
  backendClient: BackendClient,
  datasetId: string,
  label: string,
): Promise<DatasetItemRef> {
  await expect
    .poll(async () => (await backendClient.listDatasetItemsWithData(datasetId)).length, {
      timeout: 60_000,
      intervals: [500, 1_000, 2_000],
    })
    .toBe(1);

  const items = await backendClient.listDatasetItemsWithData(datasetId);
  expect(items, `${label}: the endpoint wrote exactly one item`).toHaveLength(1);
  return items[0];
}

/** An item's field names, sorted, for comparison against an exact expected set. */
const fieldsOf = (item: DatasetItemRef): string[] => Object.keys(item.data).sort();

test.describe(
  'Dataset items from traces/spans — field mappings',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'A field mapping reads from its path, beats enrichment on a collision, and is dropped when it resolves to nothing',
      { tag: ['@cap:traces.add-trace-to-dataset'] },
      async ({ fieldMappingSeed, backendClient }) => {
        // No page: every claim here is a string the backend put in the item's
        // `data`, and the dialog that sets these mappings is driven by the UI
        // test at the bottom of this file.
        await test.step('Create dataset items from the trace, with field mappings', async () => {
          const result = await backendClient.createDatasetItemsFromTraces({
            datasetId: fieldMappingSeed.datasetId,
            traceIds: [fieldMappingSeed.traceId],
            enrichment: ALL_TRACE_ENRICHMENT,
            fieldMappings: TRACE_FIELD_MAPPINGS,
          });
          expect(result.status, `create-from-traces answered: ${result.message}`).toBe(204);
        });

        const item = await readSoleItem(
          backendClient,
          fieldMappingSeed.datasetId,
          'mapped from-traces write',
        );

        await test.step('The item carries exactly the mapped and enriched fields', async () => {
          // `bogus` is absent from this list, which is the "dropped rather than
          // written as null" claim; the four enrichment fields the trace has no
          // values for (feedback_scores, comments, usage — and nothing else)
          // are absent for the same reason enrichment omits them.
          expect(fieldsOf(item)).toEqual([
            'expected_output',
            'from_metadata',
            'input',
            'metadata',
            'my_field',
            'spans',
            'tags',
          ]);
          expect(
            item.data,
            'a mapping that resolves to nothing is dropped, not written as null',
          ).not.toHaveProperty('bogus');
        });

        await test.step('Each mapping resolved its own path', async () => {
          expect(item.data.my_field, 'my_field <- input.input_text').toBe(
            FM_TRACE_INPUT.input_text,
          );
          expect(item.data.from_metadata, 'from_metadata <- metadata.mkey').toBe(
            FM_TRACE_METADATA.mkey,
          );
        });

        await test.step('The mapping beat the field enrichment would have written', async () => {
          // The discriminating assertion. Without the mapping, enrichment sets
          // `input` to the trace's whole input object — so this is only
          // satisfied by the mapping having replaced it, never by both having
          // been written.
          expect(item.data.input, 'input <- output.answer, overriding enrichment').toBe(
            FM_TRACE_OUTPUT.answer,
          );
          expect(item.data.input, 'the enriched input must not survive under its own key').not.toEqual(
            FM_TRACE_INPUT,
          );
        });

        await test.step('Every field no mapping named is the one enrichment produced', async () => {
          expect(item.data.expected_output, 'expected_output <- the trace output').toEqual(
            FM_TRACE_OUTPUT,
          );
          // Compared against the trace as STORED, not as seeded: the Python
          // SDK stamps its own `metadata.providers` onto a trace carrying an
          // LLM span, and the claim here is that enrichment copies the trace's
          // metadata across — not that the bridge wrote only what it was given.
          expect(item.data.metadata, 'metadata survives the mapping that read out of it').toEqual(
            fieldMappingSeed.traceMetadata,
          );
          expect(item.data.tags).toEqual(fieldMappingSeed.traceTags);
          expect(
            item.data.spans,
            'include_spans still attaches the trace\'s spans',
          ).toHaveLength(1);
        });
      },
    );

    test(
      'A field mapping on create-from-spans resolves against the span, including a span-only field',
      { tag: ['@cap:traces.add-trace-to-dataset'] },
      async ({ fieldMappingSeed, backendClient }) => {
        await test.step('Create dataset items from the span, with field mappings', async () => {
          const result = await backendClient.createDatasetItemsFromSpans({
            datasetId: fieldMappingSeed.datasetId,
            spanIds: [fieldMappingSeed.spanId],
            enrichment: NO_SPAN_ENRICHMENT,
            // `model` is the discriminator: a span has one and a trace does
            // not, so a resolver that had been handed the parent trace instead
            // would drop this mapping rather than resolve it to the wrong
            // value — and the key-set assertion below is what notices.
            fieldMappings: { my_field: 'input.input_text', model_used: 'model' },
          });
          expect(result.status, `create-from-spans answered: ${result.message}`).toBe(204);
        });

        const item = await readSoleItem(
          backendClient,
          fieldMappingSeed.datasetId,
          'mapped from-spans write',
        );

        await test.step('The item carries exactly the mapped fields and the span sections', async () => {
          expect(fieldsOf(item)).toEqual([
            'expected_output',
            'input',
            'model_used',
            'my_field',
          ]);
        });

        await test.step('Both mappings resolved off the span', async () => {
          expect(item.data.my_field, 'my_field <- the span\'s input.input_text').toBe(
            FM_SPAN_INPUT.input_text,
          );
          expect(item.data.model_used, 'model_used <- the span\'s model').toBe(FM_SPAN_MODEL);
        });

        await test.step('The sections no mapping named are the span\'s own', async () => {
          // Neither `input` nor `expected_output` was mapped here, so both must
          // still be the span's whole sections — this is what separates "a
          // mapping added a field" from "a mapping replaced the section it read
          // from".
          expect(item.data.input).toEqual(FM_SPAN_INPUT);
          expect(item.data.expected_output).toEqual(FM_SPAN_OUTPUT);
        });
      },
    );

    test(
      'Field mappings are ignored for a test-suite dataset',
      { tag: ['@cap:traces.add-trace-to-dataset'] },
      async ({ fieldMappingSeed, backendClient }) => {
        await test.step('Send the same mappings against the TEST_SUITE dataset', async () => {
          const result = await backendClient.createDatasetItemsFromTraces({
            datasetId: fieldMappingSeed.testSuiteId,
            traceIds: [fieldMappingSeed.traceId],
            enrichment: ALL_TRACE_ENRICHMENT,
            fieldMappings: TRACE_FIELD_MAPPINGS,
          });
          expect(
            result.status,
            `create-from-traces on a test suite answered: ${result.message}`,
          ).toBe(204);
        });

        const item = await readSoleItem(
          backendClient,
          fieldMappingSeed.testSuiteId,
          'mapped from-traces write against a test suite',
        );

        await test.step('The item is the unmapped test-suite shape', async () => {
          // A test-suite item is the trace's `input` object unwrapped into
          // top-level keys. Had the mappings been honoured, `input` would have
          // been the mapped `output.answer` string instead — not an object, so
          // it would have been wrapped back up as a single `input` key. The two
          // outcomes are therefore not just different values but different
          // shapes, which is what makes this assertion decisive.
          expect(fieldsOf(item)).toEqual(['input_text', 'noise']);
          expect(item.data.input_text).toBe(FM_TRACE_INPUT.input_text);
          expect(item.data.noise).toBe(FM_TRACE_INPUT.noise);
          expect(item.data, 'a mapped field must not reach a test suite').not.toHaveProperty(
            'my_field',
          );
        });
      },
    );

    test(
      'An unusable field mapping is rejected at validation and writes nothing',
      { tag: ['@cap:traces.add-trace-to-dataset'] },
      async ({ fieldMappingSeed, backendClient }) => {
        /**
         * The four rejections `SupportedFieldMappingPathsValidator` and the
         * request's own `@Size` are responsible for. 422, never 500: a mapping
         * the backend cannot use has to come back as a request the caller can
         * fix, and the message has to say which mapping.
         */
        const rejected: Array<{
          label: string;
          fieldMappings: Record<string, string>;
          pattern: RegExp;
        }> = [
          {
            label: 'a blank field name',
            fieldMappings: { '': 'input.input_text' },
            pattern: /name must not be blank/,
          },
          {
            label: 'a blank path',
            fieldMappings: { my_field: '   ' },
            pattern: /path must not be blank/,
          },
          {
            label: 'a path that does not compile',
            fieldMappings: { my_field: 'input[[' },
            // The reason, not the envelope. "unsupported field mappings: " is
            // the prefix `SupportedFieldMappingPathsValidator` puts on all
            // three of its branches, so matching it would be satisfied by this
            // path being rejected as a blank name — which the two cases above
            // already cover. Only the per-violation clause says the compile
            // step is what refused it.
            pattern: /is not a valid path/,
          },
          {
            label: '101 mappings, one over the cap',
            fieldMappings: Object.fromEntries(
              Array.from({ length: 101 }, (_, i) => [`field_${i}`, 'input.input_text']),
            ),
            pattern: /cannot exceed 100 entries/,
          },
        ];

        for (const { label, fieldMappings, pattern } of rejected) {
          await test.step(`${label} is rejected with 422`, async () => {
            const result = await backendClient.createDatasetItemsFromTraces({
              datasetId: fieldMappingSeed.datasetId,
              traceIds: [fieldMappingSeed.traceId],
              enrichment: ALL_TRACE_ENRICHMENT,
              fieldMappings,
            });
            expect(result.status, `${label}: answered ${result.message}`).toBe(422);
            expect(result.message, `${label}: the message says what was wrong`).toMatch(pattern);
          });
        }

        await test.step('None of the four rejections wrote an item', async () => {
          // Without this the step above would pass equally well against an
          // endpoint that answered 422 after committing the write.
          expect(await backendClient.listDatasetItemIds(fieldMappingSeed.datasetId)).toHaveLength(
            0,
          );
        });

        await test.step('A valid mapping against the same dataset still writes', async () => {
          // And without THIS, the emptiness above would be satisfied by a
          // dataset nothing could ever be written to.
          const result = await backendClient.createDatasetItemsFromTraces({
            datasetId: fieldMappingSeed.datasetId,
            traceIds: [fieldMappingSeed.traceId],
            enrichment: ALL_TRACE_ENRICHMENT,
            fieldMappings: { my_field: 'input.input_text' },
          });
          expect(result.status, `the accepted write answered: ${result.message}`).toBe(204);

          const item = await readSoleItem(
            backendClient,
            fieldMappingSeed.datasetId,
            'the accepted write',
          );
          expect(item.data.my_field).toBe(FM_TRACE_INPUT.input_text);
        });
      },
    );

    test(
      'The Add-to-dataset dialog previews an advanced mapping and writes the item it previewed',
      { tag: ['@cap:traces.add-trace-to-dataset'] },
      async ({ fieldMappingSeed, project, backendClient, page }) => {
        const logs = new LogsPage(page);

        const dialog = await test.step('Select the seeded trace and open Add to → Dataset', async () => {
          await logs.goto(project.id);
          await logs.waitForReady();
          await logs.selectTrace(fieldMappingSeed.traceId);
          return logs.openAddToDataset();
        });

        await test.step('The dialog targets the seeded dataset', async () => {
          // The project holds exactly one DATASET-type dataset (the fixture's
          // other one is a test suite, which this picker filters out), so the
          // dialog selects it on its own. Asserted rather than assumed: every
          // assertion below is about what landed in THAT dataset.
          await dialog.expectDatasetSelected(fieldMappingSeed.datasetName);
        });

        await test.step('Advanced mapping opens on the defaults it would have sent anyway', async () => {
          await dialog.enableAdvancedMapping();
          await expect(dialog.mappingRowSource('input')).toHaveText('input');
          await expect(dialog.mappingRowSource('expected_output')).toHaveText('output');
        });

        await test.step('The Preview renders the trace\'s real values under those defaults', async () => {
          // A Preview that rendered "Empty", or somebody else's trace, would
          // make the re-mapping below unobservable — this is what pins the
          // preview to the trace actually selected.
          await dialog.expectPreviewCell(
            fieldMappingSeed.traceId,
            'input',
            JSON.stringify(FM_TRACE_INPUT),
          );
          await dialog.expectPreviewCell(
            fieldMappingSeed.traceId,
            'expected_output',
            JSON.stringify(FM_TRACE_OUTPUT),
          );
        });

        await test.step('Re-pointing input at output.answer changes what the Preview shows', async () => {
          await dialog.setMappingSource('input', 'output.answer', ['output']);
          await dialog.expectPreviewCell(
            fieldMappingSeed.traceId,
            'input',
            FM_TRACE_OUTPUT.answer,
          );
        });

        await test.step('Submitting writes the item the Preview showed', async () => {
          await dialog.submit(1);

          const item = await readSoleItem(
            backendClient,
            fieldMappingSeed.datasetId,
            'the dialog\'s write',
          );
          // Read back through the API on purpose: the dialog's claim is about
          // what it is going to store, and the only place that can be checked
          // is where it was stored.
          expect(item.data.input, 'the UI mapping reached the endpoint').toBe(
            FM_TRACE_OUTPUT.answer,
          );
          expect(item.data.expected_output).toEqual(FM_TRACE_OUTPUT);
          expect(fieldsOf(item)).toEqual([
            'expected_output',
            'input',
            'metadata',
            'spans',
            'tags',
          ]);
        });
      },
    );
  },
);
