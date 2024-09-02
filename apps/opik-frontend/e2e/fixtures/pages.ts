import { FeedbackDefinitionsPage, ProjectsPage, TracesPage } from "@e2e/pages";
import {
  Fixtures,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs,
  PlaywrightWorkerOptions,
} from "@playwright/test";

export type PagesFixtures = {
  feedbackDefinitionsPage: FeedbackDefinitionsPage;
  projectsPage: ProjectsPage;
  tracesPage: TracesPage;
};

export const pagesFixtures: Fixtures<
  PagesFixtures,
  PlaywrightWorkerOptions,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs
> = {
  feedbackDefinitionsPage: async ({ page }, use) => {
    await use(new FeedbackDefinitionsPage(page));
  },
  projectsPage: async ({ page }, use) => {
    await use(new ProjectsPage(page));
  },
  tracesPage: async ({ page }, use) => {
    await use(new TracesPage(page));
  },
};
