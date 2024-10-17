import { expect, test } from "@e2e/fixtures";
import { DATASET_ITEM_1 } from "@e2e/test-data";

test.describe("Dataset items page", () => {
  test("Check adding/deleting of dataset items", async ({
    datasetItemsPage,
    dataset1,
  }) => {
    await datasetItemsPage.goto(dataset1.id);

    await datasetItemsPage.addDatasetItem(JSON.stringify(DATASET_ITEM_1.data));
    await datasetItemsPage.table.checkIsExist(DATASET_ITEM_1.data.custom);

    await datasetItemsPage.deleteDatasetItem(DATASET_ITEM_1.data.custom);
    await datasetItemsPage.table.checkIsNotExist(DATASET_ITEM_1.data.custom);
  });

  test("Check open sidebar", async ({
    page,
    dataset1,
    datasetItem1,
    datasetItemsPage,
  }) => {
    await datasetItemsPage.goto(dataset1.id);
    await datasetItemsPage.openSidebar(DATASET_ITEM_1.data.custom);
    await expect(
      page.getByTestId("dataset-items").getByRole("heading", { name: "Data" }),
    ).toBeVisible();
    await expect(
      page
        .getByTestId("dataset-items")
        .getByText(`custom: ${DATASET_ITEM_1.data.custom}`),
    ).toBeVisible();
  });
});
