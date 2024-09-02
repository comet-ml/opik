import { FeedbackDefinition, Project, Span, Trace, User } from "@e2e/entities";
import {
  CATEGORICAL_FEEDBACK_DEFINITION,
  NUMERICAL_FEEDBACK_DEFINITION,
  PROJECT_NAME,
  SPAN_NAME,
  TRACE_NAME,
} from "@e2e/test-data";
import {
  Fixtures,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs,
  PlaywrightWorkerOptions,
} from "@playwright/test";
import { v7 as uuid } from "uuid";

export type EntitiesFixtures = {
  categoricalFeedbackDefinition: FeedbackDefinition;
  numericalFeedbackDefinition: FeedbackDefinition;
  project: Project;
  // `trace` it is already taken by Playwright
  projectTrace: Trace;
  span: Span;
  user: User;
};

export const entitiesFixtures: Fixtures<
  EntitiesFixtures,
  PlaywrightWorkerOptions,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs
> = {
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

  projectTrace: async ({ project }, use) => {
    const trace = await project.addTrace(TRACE_NAME);
    await use(trace);
  },

  span: async ({ projectTrace }, use) => {
    const span = await projectTrace.addSpan(SPAN_NAME, "llm");
    await use(span);
  },

  user: async ({ page }, use) => {
    const user = new User(page);
    await use(user);
  },
};
