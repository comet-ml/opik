import { test, expect } from '@e2e/fixtures';
import { ProjectRedirectPage } from '@e2e/pom/project-redirect.page';

/**
 * `/{workspace}/redirect/projects?name=…` is how anything that only knows a
 * project by name gets a user into it — SDK "view your traces" links, the
 * onboarding snippets. Getting it wrong sends a user to a project that is not
 * the one they named.
 *
 * The name -> id mapping is not stable: a project can be deleted and a new one
 * created under the same name, at which point the name must resolve to the new
 * id. These specs pin that, cold and in-session.
 *
 * HEADS UP for whoever runs this next: the second test FAILS against staging
 * today (observed 2026-08-12, staging on 2.2.27), and it is failing because the
 * app is wrong, not because the spec is. `useProjectByName` keys its client
 * cache on the project *name* alone, and `RedirectProjects` navigates from an
 * effect off whatever id is in that cache — so an in-session return to the
 * route forwards to the id the name used to have. The refetch does land, and
 * does return the right id, but the redirect has already navigated away and
 * never corrects itself. Left deliberately un-skipped: it is a regression test
 * for a live defect, and it should go green when that defect is fixed.
 */
test.describe('Project-by-name redirect', { tag: ['@t2-cuj', '@area:projects'] }, () => {
  test(
    'resolves a project name to that project home, and follows the name when it is re-pointed',
    { tag: ['@cap:projects.project-home'] },
    async ({ repointedProjectName, page }) => {
      const redirect = new ProjectRedirectPage(page);
      const firstId = repointedProjectName.currentId;

      await test.step('The name resolves to the project that currently holds it', async () => {
        await redirect.gotoByName(repointedProjectName.name);
        expect(await redirect.waitForResolvedProjectId(), 'resolved project id').toBe(firstId);
        await expect(redirect.loadFailure, 'project home load failure').toBeHidden();
      });

      const secondId = await test.step('Re-point the name at a brand-new project', async () => {
        const id = await repointedProjectName.repoint();
        expect(id, 're-created project must be a different project').not.toBe(firstId);
        return id;
      });

      await test.step('A fresh visit resolves the new id', async () => {
        await redirect.gotoByName(repointedProjectName.name);
        expect(await redirect.waitForResolvedProjectId(), 'resolved project id after re-point')
          .toBe(secondId);
        await expect(redirect.loadFailure, 'project home load failure').toBeHidden();
      });
    },
  );

  test(
    'returning to the redirect in the same session resolves the new id, not the one cached before',
    { tag: ['@cap:projects.project-home'] },
    async ({ repointedProjectName, page }) => {
      const redirect = new ProjectRedirectPage(page);
      const firstId = repointedProjectName.currentId;

      await test.step('First visit resolves the name to the project holding it', async () => {
        await redirect.gotoByName(repointedProjectName.name);
        expect(await redirect.waitForResolvedProjectId(), 'resolved project id').toBe(firstId);
      });

      const secondId = await test.step('Re-point the name without leaving the page', async () => {
        await redirect.markSession();
        const id = await repointedProjectName.repoint();
        expect(id, 're-created project must be a different project').not.toBe(firstId);
        return id;
      });

      await test.step('Re-enter the redirect route inside the running session', async () => {
        await redirect.navigateByNameInSession(repointedProjectName.name);
        // If this navigation reloaded the page, the client cache was dropped and
        // the assertion below would be trivially satisfied. Fail loudly instead.
        await redirect.expectSessionSurvived();
      });

      await test.step('It must settle on the id the name resolves to NOW', async () => {
        // The client caches this lookup under the project *name*. If the cached
        // id is used without revalidating, the user lands on a project the name
        // no longer refers to: here that id is deleted and the page errors, but
        // where the stale id still exists the user is silently shown the wrong
        // project's data.
        expect(await redirect.waitForResolvedProjectId(), 'resolved project id in-session')
          .toBe(secondId);
        await expect(redirect.loadFailure, 'project home load failure').toBeHidden();
      });
    },
  );
});
