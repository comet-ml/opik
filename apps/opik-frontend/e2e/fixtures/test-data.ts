import { PROJECT_NAME } from "@e2e/test-data";
import {
  Fixtures,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs,
  PlaywrightWorkerOptions,
} from "@playwright/test";

export type TestDataFixtures = {
  projectName: string;
};

export const testDataFixtures: Fixtures<
  TestDataFixtures,
  PlaywrightWorkerOptions,
  PlaywrightTestArgs,
  PlaywrightWorkerArgs
> = {
  projectName: PROJECT_NAME,
};
