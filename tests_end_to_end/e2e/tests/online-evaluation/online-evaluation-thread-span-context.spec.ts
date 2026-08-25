import { test, expect } from '@e2e/fixtures';
import type { FeedbackScoreRef } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/** Turns in the seeded conversation. Two, so "per turn" is a claim that can fail. */
const TURN_COUNT = 2;

/**
 * Python source for the thread-scope metric under test.
 *
 * Kept in the spec rather than in a shared builder because only this spec uses
 * it — the shared `core/metrics` builders exist for sources more than one spec
 * needs, and a single-caller helper there would only add a hop.
 *
 * Two constraints, both easy to break by accident:
 *
 *   - **No extra `BaseMetric` imports.** The evaluator's `get_metric_class`
 *     walks module classes alphabetically and takes the first `BaseMetric`
 *     subclass, so importing one of opik's own heuristics would shadow this one.
 *   - **`score()` takes the conversation positionally.** The thread flavour of
 *     the runner calls `metric.score(data)` with the whole rendered context
 *     rather than unpacking an argument map, so the parameter name is fixed by
 *     `TraceThreadUserDefinedMetricPythonCode.CONTEXT_ARG_NAME` and there is no
 *     `arguments` map on the rule.
 *
 * The metric is the assertion. It renders every assistant turn's span tree as
 * `name:type@depth` tokens — `>` between nodes of one turn, `|` between turns —
 * so the returned `reason` states the whole structure the evaluator was handed:
 * which spans, of which type, at which nesting depth, in which turn order. A
 * flattened list would report both nodes at `@0`; a context with no spans at all
 * would report an empty node list and score 0.0.
 */
function buildThreadSpanTreeMetric(scoreName: string): string {
  return `from typing import Any, List
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}


def _walk(nodes: Any, depth: int, out: List[str]) -> None:
    for node in nodes:
        out.append("%s:%s@%d" % (node.get("name"), node.get("type"), depth))
        _walk(node.get("spans") or [], depth + 1, out)


class SpanTreeInThreadContext(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, context: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        turns = [entry for entry in (context or []) if entry.get("role") == "assistant"]
        rendered = []
        with_spans = 0
        for turn in turns:
            spans = turn.get("spans") or []
            if spans:
                with_spans += 1
            nodes: List[str] = []
            _walk(spans, 0, nodes)
            rendered.append(">".join(nodes))
        value = 1.0 if turns and with_spans == len(turns) else 0.0
        reason = "assistant_turns=%d with_spans=%d span_nodes=%s" % (
            len(turns), with_spans, "|".join(rendered))
        return score_result.ScoreResult(value=value, name=self.name, reason=reason)`;
}

test.describe('Online Evaluation — thread-scope span context', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A thread-scope Python rule receives each turn\'s spans as a nested tree', { tag: ['@cap:online-evaluation.rule-scope-thread-span'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // Worst case is the seed (two nested-trace round-trips, each of which waits
    // for its spans to be queryable), the thread close, a 180s wait for the
    // score, then the thread panel. Kept just above the inner waits so the poll
    // below fires first — its failure names the scores it actually saw, which
    // beats an opaque "test timeout exceeded". Observed runtime is 40-70s.
    test.setTimeout(300_000);

    const ruleName = `${testNamespace}-thread-spans`;
    const threadId = `${testNamespace}-thread`;

    // The score's name comes from the metric's own ScoreResult, not the rule
    // name — the engine uses the score-result name verbatim.
    const scoreName = `${testNamespace}-span-tree`;

    const spanName = (kind: 'parent' | 'child', turn: number) =>
      `${testNamespace}-${kind}-${turn}`;

    await test.step('Create the thread-scope Python rule', async () => {
      await backendClient.createThreadPythonRule({
        projectId: project.id,
        name: ruleName,
        samplingRate: 1,
        metric: buildThreadSpanTreeMetric(scoreName),
      });
    });

    await test.step(`Seed a ${TURN_COUNT}-turn thread, each turn a parent llm span with a nested tool child`, async () => {
      // Seeded through the SDK bridge, which returns only once the trace AND
      // its spans are queryable. That ordering is load-bearing: the scorer
      // reads the thread's spans when the thread closes, so spans that landed
      // after the close would be missing from the context for reasons that have
      // nothing to do with the behaviour under test.
      for (let turn = 0; turn < TURN_COUNT; turn++) {
        const created = await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${testNamespace}-turn-${turn}`,
          thread_id: threadId,
          input: { question: `question ${turn}` },
          output: { answer: `answer ${turn}` },
          duration_seconds: 1,
          spans: [
            {
              name: spanName('parent', turn),
              type: 'llm',
              input: { prompt: `prompt ${turn}` },
              output: { completion: `completion ${turn}` },
            },
            {
              // The nested child is what makes a tree distinguishable from a
              // flat list: without it every node would sit at depth 0 and the
              // assertion below could not tell the two shapes apart.
              name: spanName('child', turn),
              type: 'tool',
              input: { query: `query ${turn}` },
              output: { result: `result ${turn}` },
              parent_index: 0,
            },
          ],
        });
        expect(
          created.span_count,
          `turn ${turn} must have been seeded with both of its spans — a turn ` +
            `missing its spans would make the metric report an empty tree for ` +
            `reasons unrelated to the enrichment under test`,
        ).toBe(2);

        // Turn order is derived from start_time; without a gap two turns can
        // land in the same millisecond and their order becomes arbitrary.
        if (turn < TURN_COUNT - 1) await new Promise((r) => setTimeout(r, 50));
      }
    });

    await test.step('Close the thread so the thread-scope rule runs', async () => {
      await backendClient.closeThread({ projectName: project.name, threadId });
    });

    const score = await test.step('Wait for the rule to score the thread', async () => {
      let scores: FeedbackScoreRef[] = [];
      await expect
        .poll(
          async () => {
            scores = (await backendClient.getThread({ projectId: project.id, threadId }))
              .feedbackScores;
            return scores.some((s) => s.name === scoreName);
          },
          {
            timeout: 180_000,
            intervals: [2_000, 5_000],
            message:
              `thread '${threadId}' never received a '${scoreName}' score — a ` +
              `thread-scope rule that never ran cannot say anything about the ` +
              `context it would have been handed`,
          },
        )
        .toBe(true);

      // Named lookup rather than [0]: asserting the whole set here would fail
      // on any unrelated score, and the claim is about this rule's own result.
      const found = scores.find((s) => s.name === scoreName);
      if (!found) {
        throw new Error(`'${scoreName}' vanished between the poll and the read`);
      }
      // The reason is half of what this spec asserts, and the client's type
      // says it may be absent. Assert it away rather than coding around it with
      // a `?? ''`, which would turn a missing reason into an empty-string
      // comparison the next step could never distinguish from a wrong one.
      if (found.reason === null) {
        throw new Error(
          `'${scoreName}' landed without a reason — this metric always returns ` +
            `one, so an absent reason means the score did not come from it`,
        );
      }
      return { value: found.value, reason: found.reason };
    });

    await test.step('The metric saw a non-empty span tree on every assistant turn', async () => {
      // 1.0 only if EVERY assistant turn carried spans. Before the change under
      // test this path threaded an empty span list and `spans` was omitted from
      // the wire shape entirely, so the same metric would score 0.0 — the
      // failure mode is a clean bit flip, not a threshold.
      expect(
        score.value,
        `the metric returns 1.0 only when every assistant turn carries a ` +
          `non-empty 'spans' field; it reported: ${score.reason}`,
      ).toBe(1.0);
    });

    await test.step('Each turn carries its own spans, nested parent -> child', async () => {
      // The exact structure, not a substring of it. `toContain` would pass on a
      // context that also carried a third turn's spans, or one turn's spans
      // duplicated onto another — and cross-turn leakage is precisely the
      // mistake a per-trace grouping can make.
      const expectedTree = Array.from(
        { length: TURN_COUNT },
        (_, turn) => `${spanName('parent', turn)}:llm@0>${spanName('child', turn)}:tool@1`,
      ).join('|');
      expect(
        score.reason,
        `each assistant turn must carry only its own two spans, with the tool ` +
          `span nested one level under its llm parent (a flattened list would ` +
          `report both at @0)`,
      ).toBe(
        `assistant_turns=${TURN_COUNT} with_spans=${TURN_COUNT} span_nodes=${expectedTree}`,
      );
    });

    await test.step('The thread panel renders the score and its reason', async () => {
      const logs = new LogsPage(page);
      await logs.gotoThreads(project.id);
      await logs.waitForThreadsReady(threadId);

      const panel = await logs.openThreadById(threadId);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();

      await expect(
        panel.feedbackScoreRow(scoreName),
        'the thread score must resolve to exactly one row',
      ).toHaveCount(1);
      expect(await panel.readFeedbackScoreValue(scoreName)).toBe(score.value);
      await expect(
        panel.feedbackScoreReasonCell(scoreName),
        'the reason is where a user reads what the metric actually saw',
      ).toHaveText(score.reason);
    });
  });
});
