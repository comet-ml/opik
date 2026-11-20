import { expect } from '@playwright/test';
import { test as baseTest } from './commented-entities.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/** One turn of the searchable conversation. */
export interface ThreadSearchTurnRef {
  /** 0-based position in the conversation, oldest first. */
  index: number;
  traceId: string;
  input: string;
  output: string;
}

/**
 * Where one planted term is expected to be found, as a claim the spec asserts
 * rather than recomputes.
 */
export interface ThreadSearchTermRef {
  /** Stable handle a spec looks the term up by. */
  key: string;
  /** The term a user types. */
  term: string;
  /** Turn indices whose bodies must survive the narrowing, in render order. */
  matchingTurnIndices: number[];
  /**
   * How many ranges the `thread-search-match` highlight must hold once the
   * narrowing has happened.
   *
   * Not derivable from `matchingTurnIndices.length`: the point of the
   * three-occurrence term is that the two numbers differ, which is what pins
   * narrowing and highlighting as two separate questions. `useThreadSearch`
   * computes ranges over the already-narrowed DOM, so this counts occurrences
   * among the surviving turns only.
   */
  expectedHighlightCount: number;
}

export interface ThreadSearchTurnsRef {
  threadId: string;
  projectId: string;
  projectName: string;
  /** Turns in the order they were logged (oldest first). */
  turns: ThreadSearchTurnRef[];
  /** Two `data-message-content` bodies per turn — the number rendered unsearched. */
  totalMessageBodies: number;
  inputOnly: ThreadSearchTermRef;
  outputOnly: ThreadSearchTermRef;
  multiOccurrence: ThreadSearchTermRef;
  /** A term no seeded body contains, for the empty state. */
  nonMatchingTerm: string;
}

export interface ThreadSearchTurnsFixtures {
  threadSearchTurns: ThreadSearchTurnsRef;
}

/**
 * Three planted terms, chosen for where they are NOT as much as for where they
 * are.
 *
 * Nonsense words, lower-case, no punctuation and no shared prefixes: the search
 * regex is built with `escapeRegexSpecialChars` and the `gi` flags over the
 * whole rendered body, so a term that was also a substring of another term, of
 * the run prefix, or of any chrome text would make every count below
 * unexplainable.
 */
const INPUT_ONLY_TERM = 'zarquonix';
const OUTPUT_ONLY_TERM = 'blivetron';
const MULTI_TERM = 'quibbleforp';
const NON_MATCHING_TERM = 'grimsbyhollow';

/**
 * Six turns, with the planted terms placed so that no two of the three
 * questions this fixture poses can be satisfied by the same bug.
 *
 *   turn 0  input carries MULTI twice, in ONE sentence
 *   turn 1  input carries INPUT_ONLY
 *   turn 2  nothing planted
 *   turn 3  output carries OUTPUT_ONLY
 *   turn 4  output carries MULTI once
 *   turn 5  nothing planted
 *
 * Why each placement is load-bearing:
 *
 *  - **INPUT_ONLY is in an input and OUTPUT_ONLY in an output.**
 *    `traceMatchesSearch` matches over `prettifyMessage(trace.input)` AND
 *    `prettifyMessage(trace.output)`; a narrowing that had lost one of the two
 *    halves still answers correctly for a term in the other, so both have to be
 *    asked separately.
 *  - **MULTI appears three times across two turns**, so its highlight count (3)
 *    differs from its surviving-turn count (2) and from its body count (4). A
 *    highlighter that registered one range per matching turn, or one per
 *    matching body, satisfies neither of the other two numbers.
 *  - **The two MULTI occurrences in turn 0 share one sentence**, so they land in
 *    one text node. `findTextRanges` walks text nodes and uses `matchAll`, so
 *    the "several matches in one node" branch — the one that needs
 *    `range.setStart(node, match.index)` to be right — is only reached this way.
 *  - **Turn 0's pair renders before turn 4's single**, so stepping the current
 *    match with ArrowDown walks 0 -> 1 -> 2 -> 0 across a turn boundary, and the
 *    wrap is distinguishable from standing still.
 *  - **Turns 2 and 5 carry nothing**, so every narrowing assertion is a proper
 *    subset and "the panel never filtered" cannot pass.
 *
 * Plain-string inputs and outputs, for the reason `conversation.fixture.ts`
 * uses them: a string bypasses the chat-shape prettifiers and `MarkdownPreview`
 * renders it verbatim, so the rendered text is the seeded text and a highlight
 * offset means what it says. An object would render through the JSON view and
 * split the sentence across key and value nodes.
 */
const TURNS: Array<{ input: string; output: string }> = [
  {
    input: `The ${MULTI_TERM} review mentions ${MULTI_TERM} in one sentence on purpose.`,
    output: 'The review was filed on Monday and circulated to the whole team.',
  },
  {
    input: `Who signed off on the ${INPUT_ONLY_TERM} amendment last quarter?`,
    output: 'The regional lead signed it off before the deadline.',
  },
  {
    input: 'What time does the handover meeting start?',
    output: 'It starts at nine and runs for about half an hour.',
  },
  {
    input: 'Summarise the outstanding items for me.',
    output: `Three items remain, and the ${OUTPUT_ONLY_TERM} audit is the largest of them.`,
  },
  {
    input: 'Which of those needs a decision this week?',
    output: `Only the ${MULTI_TERM} one needs a decision before Friday.`,
  },
  {
    input: 'Thanks, that is everything for now.',
    output: 'Happy to help. Ask again whenever you need a recap.',
  },
];

/** `TraceMessage` stamps exactly two `data-message-content` nodes per turn. */
const BODIES_PER_TURN = 2;

/**
 * One six-turn conversation whose bodies carry planted search terms.
 *
 * Seeded through the Python SDK bridge turn by turn, with a short gap between
 * them, for the reason `conversation.fixture.ts` does: the panel sorts its
 * turns by trace id, the SDK mints time-ordered UUIDv7 ids, and the spec's
 * current-match indices are positions in RENDER order. The spec asserts the
 * rendered order equals the seeded order before it steps through anything, so
 * an ordering surprise fails where it happened rather than shifting every index
 * by one.
 *
 * Teardown deletes the traces explicitly rather than leaning on the project
 * cascade: this thread's turns are the only thing it creates, and a leaked turn
 * would change the body count the next run of this spec asserts.
 */
export const test = baseTest.extend<ThreadSearchTurnsFixtures>({
  threadSearchTurns: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    // The planted terms have to be absent from every body that is not meant to
    // carry them, or none of the counts below mean anything. Asserted over the
    // seed text itself, before a single write, because this is a property of
    // the table above that a well-meant edit to a sentence can silently break.
    const occurrences = (haystack: string, needle: string): number =>
      haystack.toLowerCase().split(needle.toLowerCase()).length - 1;
    const planted: Array<{ term: string; perTurn: number[] }> = [
      INPUT_ONLY_TERM,
      OUTPUT_ONLY_TERM,
      MULTI_TERM,
      NON_MATCHING_TERM,
    ].map((term) => ({
      term,
      perTurn: TURNS.map((t) => occurrences(t.input, term) + occurrences(t.output, term)),
    }));
    const byTerm = new Map(planted.map((p) => [p.term, p.perTurn]));
    expect(byTerm.get(INPUT_ONLY_TERM), `${INPUT_ONLY_TERM} appears once, in turn 1's input`).toEqual(
      [0, 1, 0, 0, 0, 0],
    );
    expect(
      byTerm.get(OUTPUT_ONLY_TERM),
      `${OUTPUT_ONLY_TERM} appears once, in turn 3's output`,
    ).toEqual([0, 0, 0, 1, 0, 0]);
    expect(
      byTerm.get(MULTI_TERM),
      `${MULTI_TERM} appears twice in turn 0 and once in turn 4 — the counts the highlight ` +
        'assertions are built on',
    ).toEqual([2, 0, 0, 0, 1, 0]);
    expect(
      byTerm.get(NON_MATCHING_TERM),
      `${NON_MATCHING_TERM} must appear nowhere, or the empty state can never render`,
    ).toEqual([0, 0, 0, 0, 0, 0]);
    // Exactly the placement the doc comment claims: a term in an input only and
    // a term in an output only. A seed where both ended up on the same side
    // would quietly stop testing half of `traceMatchesSearch`.
    expect(
      TURNS.map((t) => occurrences(t.output, INPUT_ONLY_TERM)).reduce((a, b) => a + b, 0),
      `${INPUT_ONLY_TERM} must not appear in any OUTPUT`,
    ).toBe(0);
    expect(
      TURNS.map((t) => occurrences(t.input, OUTPUT_ONLY_TERM)).reduce((a, b) => a + b, 0),
      `${OUTPUT_ONLY_TERM} must not appear in any INPUT`,
    ).toBe(0);

    const threadId = `${testNamespace}-search-thread`;
    const turns: ThreadSearchTurnRef[] = [];

    try {
      for (let i = 0; i < TURNS.length; i++) {
        const { input, output } = TURNS[i];
        const created = await sdkClient.python.createTrace({
          project_name: project.name,
          name: `${testNamespace}-search-turn-${i}`,
          input,
          output,
          thread_id: threadId,
        });
        turns.push({ index: i, traceId: created.id, input, output });
        if (i < TURNS.length - 1) {
          await new Promise((r) => setTimeout(r, 50));
        }
      }

      const ref: ThreadSearchTurnsRef = {
        threadId,
        projectId: project.id,
        projectName: project.name,
        turns,
        totalMessageBodies: TURNS.length * BODIES_PER_TURN,
        inputOnly: {
          key: 'input-only',
          term: INPUT_ONLY_TERM,
          matchingTurnIndices: [1],
          expectedHighlightCount: 1,
        },
        outputOnly: {
          key: 'output-only',
          term: OUTPUT_ONLY_TERM,
          matchingTurnIndices: [3],
          expectedHighlightCount: 1,
        },
        multiOccurrence: {
          key: 'multi-occurrence',
          term: MULTI_TERM,
          matchingTurnIndices: [0, 4],
          expectedHighlightCount: 3,
        },
        nonMatchingTerm: NON_MATCHING_TERM,
      };

      // The three questions must stay three questions: if the multi-occurrence
      // term's highlight count ever equalled its turn count or its body count,
      // a highlighter that counted the wrong thing would pass.
      expect(
        new Set([
          ref.multiOccurrence.expectedHighlightCount,
          ref.multiOccurrence.matchingTurnIndices.length,
          ref.multiOccurrence.matchingTurnIndices.length * BODIES_PER_TURN,
        ]).size,
        'the multi-occurrence term must expect three DIFFERENT numbers for ranges, turns and bodies',
      ).toBe(3);

      await testInfo.attach('opik.threadSearchTurns', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const ids = turns.map((t) => t.traceId);
        if (ids.length > 0) {
          try {
            await backendClient.deleteTraces(ids);
          } catch (err) {
            console.warn(
              `[threadSearchTurns fixture] delete warning for traces ${ids.join(', ')}:`,
              err,
            );
          }
        }
      }
    }
  },
});

export { expect } from './commented-entities.fixture';
