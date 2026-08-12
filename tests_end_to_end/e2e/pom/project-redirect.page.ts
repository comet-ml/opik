import { expect, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The `/{workspace}/redirect/projects?name=…` entry point, which resolves a
 * project *name* to an id and forwards to that project's home. It is how the
 * SDK's "view your traces" links and the onboarding snippets get a user into a
 * project they only know by name.
 */
export class ProjectRedirectPage {
  constructor(private readonly page: Page) {}

  private redirectUrl(projectName: string): string {
    // Built off baseUrl by concatenation, like every other POM here: baseUrl
    // carries a path prefix on hosted deployments (…/opik), and resolving a
    // root-relative path against it would silently drop that prefix.
    const env = loadEnvConfig();
    return `${env.baseUrl}/${env.workspace}/redirect/projects?name=${encodeURIComponent(projectName)}`;
  }

  /** Full page load of the redirect route — a cold start, with no client cache. */
  async gotoByName(projectName: string): Promise<void> {
    await this.page.goto(this.redirectUrl(projectName));
  }

  /**
   * Re-enter the redirect route *within the running SPA session*, so whatever
   * the client cached on the previous visit is still live. This is the only
   * way to observe cache-scoping behaviour: a `goto()` would drop the cache and
   * every resolution would trivially be correct.
   *
   * There is no in-app link to this route from a normal workspace, so the
   * navigation is pushed onto the history the router listens to. Callers should
   * pair this with `markSession()`/`expectSessionSurvived()` so a navigation
   * that silently became a full reload fails loudly instead of passing for the
   * wrong reason.
   */
  async navigateByNameInSession(projectName: string): Promise<void> {
    const url = new URL(this.redirectUrl(projectName));
    await this.page.evaluate((target) => {
      window.history.pushState({}, '', target);
      window.dispatchEvent(new PopStateEvent('popstate', { state: {} }));
    }, `${url.pathname}${url.search}`);
  }

  private static readonly SESSION_MARKER = '__opikE2eSessionMarker';

  /** Stamp the live page so a later reload can be detected. */
  async markSession(): Promise<void> {
    await this.page.evaluate((key) => {
      (window as unknown as Record<string, unknown>)[key] = 'alive';
    }, ProjectRedirectPage.SESSION_MARKER);
  }

  async expectSessionSurvived(): Promise<void> {
    const marker = await this.page.evaluate(
      (key) => (window as unknown as Record<string, unknown>)[key],
      ProjectRedirectPage.SESSION_MARKER,
    );
    expect(marker, 'in-session navigation must not have reloaded the page').toBe('alive');
  }

  /**
   * Wait for the redirect to settle on a project home URL and return the id it
   * chose. Settling is what matters: the route navigates from an effect, so the
   * URL can change more than once before it stops.
   */
  async waitForResolvedProjectId(timeout = 15_000): Promise<string> {
    await this.page.waitForURL(/\/projects\/[0-9a-fA-F-]{36}\/home/, { timeout });
    const match = new URL(this.page.url()).pathname.match(
      /\/projects\/([0-9a-fA-F-]{36})\/home/,
    );
    if (!match) {
      throw new Error(
        `ProjectRedirectPage.waitForResolvedProjectId: settled on an unexpected URL ${this.page.url()}`,
      );
    }
    return match[1];
  }

  /** Shown by ProjectPage when the id in the URL cannot be loaded. */
  get loadFailure(): Locator {
    return this.page.getByText('Failed to load the project');
  }

  /** Shown by RedirectProjects when the name resolves to nothing. */
  get projectNotFound(): Locator {
    return this.page.getByText('This project could not be found');
  }
}
