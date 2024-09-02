import { test as base } from "@playwright/test";
import { entitiesFixtures, EntitiesFixtures } from "./entities";
import { pagesFixtures, PagesFixtures } from "./pages";
import { testDataFixtures, TestDataFixtures } from "./test-data";

type Fixtures = EntitiesFixtures & PagesFixtures & TestDataFixtures;

export const test = base.extend<Fixtures>({
  ...entitiesFixtures,
  ...pagesFixtures,
  ...testDataFixtures,
});

export { expect } from "@playwright/test";
