import { test as baseTest, expect } from './field-mapping-seed.fixture';

/** One seeded trace, plus the tag it alone carries before anything is bulk-applied. */
export interface BulkTagTraceRef {
  id: string;
  name: string;
  /** Carried by this trace and no other, so a merge can be told from a replace. */
  ownTag: string;
}

export interface BulkTagTracesFixtures {
  bulkTagTraces: {
    all: BulkTagTraceRef[];
    /** Indices of `all` the spec selects in the table. */
    selected: number[];
    /** Indices of `all` nothing in the spec ever touches. */
    bystanders: number[];
  };
}

const TRACE_COUNT = 5;
/**
 * Three of five, and deliberately NOT a contiguous block: a batch that
 * over-reached by taking a range between the first and last selected id would
 * still look correct against 0,1,2 — index 2 is the bystander that sits inside
 * the selection's span and must come back untouched.
 */
const SELECTED = [0, 1, 3];
const BYSTANDERS = [2, 4];

/**
 * Five traces in one project, each carrying a distinct pre-existing tag.
 *
 * The distinct tags are the whole point. `PATCH /v1/private/traces/batch` adds
 * and removes tags across a set, and both ways it can be wrong are invisible in
 * the table afterwards: a batch that over-reaches tags rows nobody selected, and
 * one that replaces rather than merges silently drops whatever each row already
 * had. Giving every trace a tag of its own makes both failures assertable —
 * "trace 2 still has exactly its own tag" and "trace 1 has its own AND the new
 * one" are different answers from "every trace has the new tag".
 */
export const test = baseTest.extend<BulkTagTracesFixtures>({
  bulkTagTraces: async ({ sdkClient, project, testNamespace }, use, testInfo) => {
    const all: BulkTagTraceRef[] = [];
    for (let i = 0; i < TRACE_COUNT; i++) {
      const name = `${testNamespace}-bulktag-${i}`;
      const ownTag = `${testNamespace}-own-${i}`;
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name,
        input: { question: `question ${i}` },
        output: { answer: `answer ${i}` },
        tags: [ownTag],
        spans: [],
      });
      all.push({ id: created.id, name: created.name, ownTag });
    }

    await testInfo.attach('opik.bulkTagTraces', {
      body: JSON.stringify(all, null, 2),
      contentType: 'application/json',
    });

    await use({ all, selected: SELECTED, bystanders: BYSTANDERS });
    // No explicit teardown — the project fixture's deleteProject cascades.
  },
});

export { expect };
