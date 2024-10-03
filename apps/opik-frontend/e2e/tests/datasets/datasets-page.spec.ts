import { expect, test } from "@e2e/fixtures";
import { DATASET_1 } from "@e2e/test-data";

test.describe("Datasets page", () => {
  test("Check search", async ({ datasetsPage, dataset1, dataset2 }) => {
    await datasetsPage.goto();
    await expect(datasetsPage.title).toBeVisible();

    await datasetsPage.search.search(dataset1.name);
    await datasetsPage.table.hasRowCount(1);

    await datasetsPage.search.search(dataset2.name);
    await datasetsPage.table.hasRowCount(2);

    await datasetsPage.search.search("invalid_search_string");
    await datasetsPage.table.hasNoData();
  });

  test("Check moving to dataset item page", async ({
    page,
    dataset1,
    datasetsPage,
  }) => {
    await datasetsPage.goto();
    await datasetsPage.goToDataset(dataset1.name);
    await expect(
      page.getByRole("heading", { name: dataset1.name }),
    ).toBeVisible();
  });

  test("Check adding/deleting of datasets", async ({ datasetsPage }) => {
    await datasetsPage.goto();

    await datasetsPage.addDataset(DATASET_1.name, DATASET_1.description);
    await datasetsPage.table.checkIsExist(DATASET_1.name);

    await datasetsPage.deleteDataset(DATASET_1.name);
    await datasetsPage.table.checkIsNotExist(DATASET_1.name);
  });
});
