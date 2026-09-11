import { test as baseTest } from './token-usage-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface AutomationRulesCleanupFixtures {
  /**
   * Deletes the automation rules a test created under its fixture project.
   *
   * Requesting this fixture is the whole API — there is nothing to call. Rules
   * are discovered from the project at teardown rather than registered by the
   * test, so a rule created moments before a failure is still cleaned up; an
   * id-registration API would leak exactly those.
   */
  automationRulesCleanup: void;
}

/**
 * Teardown for automation rules created against the `project` fixture.
 *
 * Needed because rules do NOT cascade with their project: `ProjectService`'s
 * delete path does not touch `automation_rule_projects`, so a project fixture
 * teardown leaves every rule behind. Any spec that creates online-evaluation
 * rules therefore has to delete them, and doing it in-test is fragile — a
 * failing assertion skips a trailing cleanup step, and moving that step into a
 * `finally` block invites two subtler bugs (see below).
 *
 * A fixture is the right home for three reasons:
 *
 *   1. Playwright runs fixture teardown regardless of test outcome, so cleanup
 *      cannot be skipped by a failure the way a trailing `test.step` is.
 *   2. It gets a settled `testInfo`, so `shouldLeaveArtifacts` actually works.
 *      Inside a test's own `finally` the status is not final yet — it still
 *      reads 'passed' on a failing test — so a retention check there silently
 *      never fires.
 *   3. It cannot swallow the test's failure. A `return` inside a test's
 *      `finally` discards the in-flight exception and resolves the test, which
 *      reports a genuinely failing test as PASSED. There is no such hazard in
 *      fixture teardown.
 *
 * Deletion is workspace-global, not project-scoped: the delete path constrains
 * only `workspace_id` and the rule id, so removing a rule that also targets
 * another project destroys it there too. Rules are therefore skipped unless the
 * fixture project is their sole target.
 *
 * Best-effort throughout: a cleanup failure warns rather than throws, so it
 * cannot mask the assertion or scoring error that explains the run. Leaked
 * rules are a tidiness problem; a masked failure costs a debugging session.
 */
export const test = baseTest.extend<AutomationRulesCleanupFixtures>({
  automationRulesCleanup: [
    async ({ project, backendClient }, use, testInfo) => {
      await use();

      if (shouldLeaveArtifacts(testInfo)) {
        console.warn(
          `[automationRulesCleanup] leaving rules under ${project.name} for debugging`,
        );
        return;
      }

      let rules;
      try {
        rules = await backendClient.listAutomationRulesForProject(project.id);
      } catch (err) {
        console.warn('[automationRulesCleanup] could not list rules:', err);
        return;
      }

      for (const rule of rules) {
        const sharedWith = rule.projectIds.filter((id) => id !== project.id);
        if (sharedWith.length > 0) {
          console.warn(
            `[automationRulesCleanup] skipping ${rule.name}: also targets ` +
              `${sharedWith.join(', ')}, and deletion is workspace-global`,
          );
          continue;
        }
        try {
          await backendClient.deleteAutomationRule(project.id, rule.id);
        } catch (err) {
          // Keep going: one undeletable rule must not orphan the rest.
          console.warn(`[automationRulesCleanup] could not delete ${rule.name}:`, err);
        }
      }
    },
    // `auto: false` — a spec opts in by naming the fixture, which keeps the
    // teardown's cost off every other test in the suite.
    { auto: false },
  ],
});

export { expect } from './token-usage-spans.fixture';
