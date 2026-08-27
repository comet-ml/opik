import { test as baseTest, expect } from './bystander.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * The token the search box is driven with. Deliberately nonsense: the backend
 * searches a trace's name, input, output, metadata, tags and thread_id, and
 * every seeded entity is named from `testNamespace` (which is derived from the
 * test title). A token that could appear in a title — "export", "search" —
 * would match the decoys too and the fixture would silently stop
 * discriminating.
 */
export const EXPORT_SEARCH_TOKEN = 'zkwidget';

/** Same reasoning, for the threads shape. */
export const EXPORT_SEARCH_THREAD_TOKEN = 'zkthread';

/** Matching entities seeded. Two get selected, so >2 proves the export is
 *  scoped to the selection and not to "everything the search matched". */
const MATCHING = 4;

/** Non-matching decoys. Their presence proves the search narrowed at all. */
const DECOYS = 3;

/** How many of the matching rows the test ticks before exporting. */
export const EXPORT_SELECTION_SIZE = 2;

/**
 * The exported CSV column holding a trace's seeded input.
 *
 * Traces are seeded with an explicit `{ query: ... }` input rather than a bare
 * string for exactly this reason: `json2csv` flattens a nested object into
 * dotted column names, so the column name is one the fixture chose and a
 * reader of the spec can trace back here. Seeding a plain string instead would
 * land it under `input._input_value` — the Python SDK's own wrapper key for a
 * non-dict input, which reads like an implementation leak in the assertion.
 */
export const TRACE_INPUT_COLUMN = 'input.query';

/**
 * The exported CSV column holding a thread's first message. Threads are seeded
 * with the same `{ query: ... }` turn shape as the traces above, and a thread's
 * `first_message` is its first turn's input verbatim — so the same flattening
 * applies one level down.
 */
export const THREAD_FIRST_MESSAGE_COLUMN = 'first_message.query';

export interface ExportSearchTraceRef {
  id: string;
  name: string;
  /** The trace's `input.query`, verbatim — the column the export is matched on. */
  input: string;
  /** True when the input carries EXPORT_SEARCH_TOKEN. */
  matches: boolean;
}

export interface ExportSearchThreadRef {
  threadId: string;
  /** First turn's input text — what the Threads export's `first_message` carries. */
  firstMessage: string;
  matches: boolean;
}

export interface ExportSearchFixtures {
  exportSearchTraces: {
    all: ExportSearchTraceRef[];
    matching: ExportSearchTraceRef[];
    decoys: ExportSearchTraceRef[];
    token: string;
  };
  exportSearchThreads: {
    all: ExportSearchThreadRef[];
    matching: ExportSearchThreadRef[];
    decoys: ExportSearchThreadRef[];
    token: string;
  };
}

/**
 * Payloads are plain strings with no comma, quote or newline in them. The
 * Traces export renders `input`/`output` straight into a CSV field, so a
 * payload carrying a delimiter would need a full CSV parser to read back —
 * this keeps the assertion about the exported *rows* rather than about
 * quoting. Objects are avoided for the same reason: json2csv flattens them
 * into `input.query`-style columns.
 */
const traceInput = (token: string, index: number) => `${token}-payload-${index}`;

export const test = baseTest.extend<ExportSearchFixtures>({
  /**
   * Seven traces in one project: four whose input carries the search token and
   * three that do not. The split is what makes the export assertion meaningful
   * — an export that ignored the selection would hold four rows, one that
   * ignored the search could hold seven, and the correct one holds exactly the
   * two that were ticked.
   */
  exportSearchTraces: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const all: ExportSearchTraceRef[] = [];

    for (let i = 1; i <= MATCHING + DECOYS; i++) {
      const matches = i <= MATCHING;
      const token = matches ? EXPORT_SEARCH_TOKEN : 'zkdecoy';
      const input = traceInput(token, i);
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-${token}-${i}`,
        input: { query: input },
        output: { answer: `${token}-answer-${i}` },
        spans: [],
      });
      all.push({ id: created.id, name: created.name, input, matches });
    }

    const matching = all.filter((t) => t.matches);
    const decoys = all.filter((t) => !t.matches);

    // Prove the fixture discriminates before any browser opens. A UI assertion
    // over a seed that failed to split would be a test that cannot fail: if the
    // token matched all seven (or none), "the table shows the matching rows"
    // would still read as green while verifying nothing.
    const searched = await backendClient.listTraceIds({
      projectId: project.id,
      search: EXPORT_SEARCH_TOKEN,
    });
    const searchedSet = new Set(searched);
    if (searchedSet.size !== matching.length) {
      throw new Error(
        `exportSearchTraces: expected the API search for "${EXPORT_SEARCH_TOKEN}" to return ` +
          `${matching.length} traces, got ${searchedSet.size}`,
      );
    }
    for (const trace of matching) {
      if (!searchedSet.has(trace.id)) {
        throw new Error(`exportSearchTraces: search missed seeded match ${trace.name}`);
      }
    }

    await testInfo.attach('opik.exportSearchTraces', {
      body: JSON.stringify(all, null, 2),
      contentType: 'application/json',
    });

    await use({ all, matching, decoys, token: EXPORT_SEARCH_TOKEN });

    // Traces are deleted explicitly rather than left to the project delete:
    // the project fixture's deleteProject removes the project row, and the
    // run-prefix sweep in global-teardown only knows about projects, datasets
    // and experiments — neither is a documented cascade for trace rows.
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(all.map((t) => t.id));
      } catch (err) {
        console.warn('[exportSearchTraces fixture] trace delete warning:', err);
      }
    }
  },

  /**
   * Seven threads in one project — four whose thread_id carries the search
   * token and three that do not — each logged as two traces sharing that
   * thread_id, so every thread has a real first and last message to export.
   */
  exportSearchThreads: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const all: ExportSearchThreadRef[] = [];
    const traceIds: string[] = [];

    for (let i = 1; i <= MATCHING + DECOYS; i++) {
      const matches = i <= MATCHING;
      const token = matches ? EXPORT_SEARCH_THREAD_TOKEN : 'zkdecoy';
      const threadId = `${testNamespace}-${token}-${i}`;
      const firstMessage = `${token}-turn-1-of-${i}`;

      for (let turn = 1; turn <= 2; turn++) {
        const created = await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${threadId}-turn-${turn}`,
          input: { query: turn === 1 ? firstMessage : `${token}-turn-2-of-${i}` },
          output: { answer: `${token}-reply-${turn}-of-${i}` },
          thread_id: threadId,
          spans: [],
        });
        traceIds.push(created.id);
      }

      all.push({ threadId, firstMessage, matches });
    }

    const matching = all.filter((t) => t.matches);
    const decoys = all.filter((t) => !t.matches);

    // Same discrimination gate as the traces shape, against the threads read.
    // Threads are aggregated from traces and are eventually consistent, so this
    // polls rather than asserting once — but it still asserts the exact split,
    // never "at least the ones I seeded".
    await expect
      .poll(
        async () => {
          const { threads } = await backendClient.listThreads({
            projectId: project.id,
            search: EXPORT_SEARCH_THREAD_TOKEN,
          });
          return threads.map((t) => t.id).sort();
        },
        {
          timeout: 60_000,
          intervals: [500, 1000, 2000],
          message: `API search for "${EXPORT_SEARCH_THREAD_TOKEN}" never settled on the ${matching.length} seeded threads`,
        },
      )
      .toEqual(matching.map((t) => t.threadId).sort());

    await testInfo.attach('opik.exportSearchThreads', {
      body: JSON.stringify(all, null, 2),
      contentType: 'application/json',
    });

    await use({ all, matching, decoys, token: EXPORT_SEARCH_THREAD_TOKEN });

    // Deleting the traces removes the threads: a thread is an aggregate over
    // the traces carrying its thread_id, with no row of its own to delete.
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(traceIds);
      } catch (err) {
        console.warn('[exportSearchThreads fixture] trace delete warning:', err);
      }
    }
  },
});

export { expect } from './bystander.fixture';
