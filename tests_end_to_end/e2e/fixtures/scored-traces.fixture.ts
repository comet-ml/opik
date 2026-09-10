import { test as baseTest, expect } from './alert.fixture';

export interface ScoredTracesRef {
  /** The two feedback-score names, distinct so an assertion can tell the rows apart. */
  scoreNames: [string, string];
  traceIds: string[];
}

export interface ScoredTracesFixtures {
  /**
   * Two traces under the `project` fixture, each carrying a differently-named
   * trace feedback score.
   *
   * Exists for the alert condition builder: its score select is populated from
   * `GET /v1/private/traces/feedback-scores/names?project_id=…`, so a project
   * with no scored traces offers an empty dropdown and every condition row is
   * unfillable. Two names rather than one because the assertions are about
   * *which* row a value landed on — a single name would compare equal however
   * the builder shuffled them.
   */
  scoredTraces: ScoredTracesRef;
}

/**
 * Names are suffixes of the run-namespaced trace name, so the sweep in
 * `global-teardown.ts` recognises them and two concurrent runs never see each
 * other's scores in the dropdown.
 */
export const test = baseTest.extend<ScoredTracesFixtures>({
  scoredTraces: async ({ sdkClient, project, backendClient, testNamespace }, use, testInfo) => {
    const scoreNames: [string, string] = [
      `${testNamespace}-score-alpha`,
      `${testNamespace}-score-beta`,
    ];

    const traceIds: string[] = [];
    for (const [index, scoreName] of scoreNames.entries()) {
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-scored-${index}`,
        input: { query: `question ${index}` },
        output: { answer: `answer ${index}` },
        feedback_scores: [{ name: scoreName, value: 0.5 }],
        spans: [],
      });
      traceIds.push(created.id);
    }

    // Prove the fixture really set the state up, before any test opens a
    // browser: a UI assertion over a dropdown that silently came back empty is
    // a test that cannot fail, and reads as coverage forever. Waiting on the
    // trace's own scores would not be enough — the select reads the aggregated
    // names endpoint, which lands separately.
    for (const [index, traceId] of traceIds.entries()) {
      await backendClient.pollTraceForFeedbackScore(traceId, scoreNames[index]);
    }
    await expect
      .poll(() => backendClient.listTraceFeedbackScoreNames(project.id), {
        message: 'seeded score names must reach the aggregation the select reads',
        timeout: 60_000,
      })
      .toEqual(expect.arrayContaining(scoreNames));

    await testInfo.attach('opik.scoredTraces', {
      body: JSON.stringify({ scoreNames, traceIds }, null, 2),
      contentType: 'application/json',
    });

    // No explicit teardown: traces go with the `project` fixture's delete, the
    // same as `filterableTraces`.
    await use({ scoreNames, traceIds });
  },
});

export { expect };
