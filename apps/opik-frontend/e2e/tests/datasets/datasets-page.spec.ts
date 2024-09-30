import { expect, test } from "@e2e/fixtures";
import { DATASET_1 } from "@e2e/test-data";

test.describe("Datasets page", () => {
  test("Check search", async ({ datasetPage, dataset1, dataset2 }) => {
    await datasetPage.goto();
    await expect(datasetPage.title).toBeVisible();

    // check if Default project exists
    await datasetPage.table.hasRowCount(2);

    await datasetPage.search.search(dataset1.name);
    await datasetPage.table.hasRowCount(1);

    await datasetPage.search.search(dataset2.name);
    await datasetPage.table.hasRowCount(2);

    await datasetPage.search.search("invalid_search_string");
    await datasetPage.table.hasNoData();
  });

  test("Check moving to dataset item page", async ({
    page,
    dataset1,
    datasetPage,
  }) => {
    await datasetPage.goto();
    await datasetPage.goToDataset(dataset1.name);
    await expect(
      page.getByRole("heading", { name: dataset1.name }),
    ).toBeVisible();
  });

  test("Check adding/deleting of datasets", async ({ datasetPage }) => {
    await datasetPage.goto();

    await datasetPage.addDataset(DATASET_1.name, DATASET_1.description);
    await datasetPage.checkIsExistOnTable(DATASET_1.name);

    await datasetPage.deleteDataset(DATASET_1.name);
    await datasetPage.checkIsNotExistOnTable(DATASET_1.name);
  });
});
