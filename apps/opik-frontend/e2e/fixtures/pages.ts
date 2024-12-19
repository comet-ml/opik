import {
  DatasetItemsPage,
  DatasetsPage,
  FeedbackDefinitionsTab,
  ProjectsPage,
  TracesPage,
} from "@e2e/pages";
import {
  Fixtures,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs,
  PlaywrightWorkerOptions,
} from "@playwright/test";
import { ConfigurationPage } from "@e2e/pages/ConfigurationPage/ConfigurationPage";

export type PagesFixtures = {
  datasetsPage: DatasetsPage;
  datasetItemsPage: DatasetItemsPage;
  projectsPage: ProjectsPage;
  tracesPage: TracesPage;
  configurationPage: ConfigurationPage;
  feedbackDefinitionsTab: FeedbackDefinitionsTab;
};

export const pagesFixtures: Fixtures<
  PagesFixtures,
  PlaywrightWorkerOptions,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs
> = {
  datasetsPage: async ({ page }, use) => {
    await use(new DatasetsPage(page));
  },
  datasetItemsPage: async ({ page }, use) => {
    await use(new DatasetItemsPage(page));
  },

  projectsPage: async ({ page }, use) => {
    await use(new ProjectsPage(page));
  },

  tracesPage: async ({ page }, use) => {
    await use(new TracesPage(page));
  },

  configurationPage: async ({ page }, use) => {
    await use(new ConfigurationPage(page));
  },

  feedbackDefinitionsTab: async ({ page }, use) => {
    await use(new FeedbackDefinitionsTab(page));
  },
};
