import {
  DatasetsPage,
  FeedbackDefinitionsPage,
  ProjectsPage,
  TracesPage,
} from "@e2e/pages";
import {
  Fixtures,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs,
  PlaywrightWorkerOptions,
} from "@playwright/test";

export type PagesFixtures = {
  datasetPage: DatasetsPage;
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
  datasetPage: async ({ page }, use) => {
    await use(new DatasetsPage(page));
  },
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
