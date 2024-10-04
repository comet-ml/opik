import {
  FeedbackDefinition,
  Project,
  Span,
  Trace,
  User,
  Dataset,
  DatasetItem,
} from "@e2e/entities";
import {
  CATEGORICAL_FEEDBACK_DEFINITION,
  DATASET_1,
  DATASET_2,
  DATASET_ITEM_1,
  DATASET_ITEM_2,
  NUMERICAL_FEEDBACK_DEFINITION,
  PROJECT_NAME,
  SPAN_1,
  SPAN_2,
  TRACE_1,
  TRACE_2,
} from "@e2e/test-data";
import {
  Fixtures,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs,
  PlaywrightWorkerOptions,
} from "@playwright/test";
import { v7 as uuid } from "uuid";

export type EntitiesFixtures = {
  dataset1: Dataset;
  dataset2: Dataset;
  datasetItem1: DatasetItem;
  datasetItem2: DatasetItem;
  categoricalFeedbackDefinition: FeedbackDefinition;
  numericalFeedbackDefinition: FeedbackDefinition;
  project: Project;
  trace1: Trace;
  trace2: Trace;
  span: Span;
  user: User;
};

export const entitiesFixtures: Fixtures<
  EntitiesFixtures,
  PlaywrightWorkerOptions,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs
> = {
  dataset1: async ({ page }, use) => {
    const dataset = await Dataset.create(page, DATASET_1.name, {
      description: DATASET_1.description,
    });
    await use(dataset);
    await dataset.destroy();
  },

  dataset2: async ({ page }, use) => {
    const dataset = await Dataset.create(page, DATASET_2.name);
    await use(dataset);
    await dataset.destroy();
  },

  datasetItem1: async ({ dataset1 }, use) => {
    const datasetItem = await dataset1.addItem(DATASET_ITEM_1);
    await use(datasetItem);
    await datasetItem.destroy();
  },

  datasetItem2: async ({ dataset1 }, use) => {
    const datasetItem = await dataset1.addItem(DATASET_ITEM_2);
    await use(datasetItem);
    await datasetItem.destroy();
  },

  categoricalFeedbackDefinition: async ({ page }, use) => {
    const categoricalFeedbackDefinition = await FeedbackDefinition.create(
      page,
      CATEGORICAL_FEEDBACK_DEFINITION.name,
      CATEGORICAL_FEEDBACK_DEFINITION.type,
      CATEGORICAL_FEEDBACK_DEFINITION.details,
    );
    await use(categoricalFeedbackDefinition);
    await categoricalFeedbackDefinition.destroy();
  },

  numericalFeedbackDefinition: async ({ page }, use) => {
    const numericalFeedbackDefinition = await FeedbackDefinition.create(
      page,
      NUMERICAL_FEEDBACK_DEFINITION.name,
      NUMERICAL_FEEDBACK_DEFINITION.type,
      NUMERICAL_FEEDBACK_DEFINITION.details,
    );
    await use(numericalFeedbackDefinition);
    await numericalFeedbackDefinition.destroy();
  },

  project: async ({ user }, use) => {
    const project = await user.addProject(PROJECT_NAME + uuid());
    await use(project);
    await project.destroy();
  },

  trace1: async ({ project }, use) => {
    const trace = await project.addTrace(TRACE_1.name, TRACE_1);
    await use(trace);
  },

  trace2: async ({ project }, use) => {
    const trace = await project.addTrace(TRACE_2.name, TRACE_2);
    await use(trace);
  },

  span: async ({ trace1 }, use) => {
    const span = await trace1.addSpan(SPAN_1.name, SPAN_1);
    await span.addSpan(SPAN_2.name, {
      ...SPAN_2,
      parent_span_id: span.id,
    });
    await use(span);
  },

  user: async ({ page }, use) => {
    const user = new User(page);
    await use(user);
  },
};
