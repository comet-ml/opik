import { expect, test } from "@e2e/fixtures";
import { DATASET_ITEM_1 } from "@e2e/test-data";

test.describe("Dataset items page", () => {
  test("Check adding/deleting of dataset items", async ({
    datasetItemsPage,
    dataset1,
  }) => {
    await datasetItemsPage.goto(dataset1.id);

    await datasetItemsPage.addDatasetItem(
      JSON.stringify(DATASET_ITEM_1.input),
      JSON.stringify(DATASET_ITEM_1.expected_output),
      JSON.stringify(DATASET_ITEM_1.metadata),
    );

    const input = `{ "prompt": "${DATASET_ITEM_1.input.prompt}" }`;

    await datasetItemsPage.checkIsExistOnTable(input);

    await datasetItemsPage.deleteDatasetItem(input);
    await datasetItemsPage.checkIsNotExistOnTable(input);
  });

  test("Check open sidebar", async ({
    page,
    dataset1,
    datasetItem1,
    datasetItemsPage,
  }) => {
    await datasetItemsPage.goto(dataset1.id);
    const input = `{ "prompt": "${
      (datasetItem1.input as { prompt: string }).prompt
    }" }`;
    await datasetItemsPage.openSidebar(input);
    await expect(page.getByText(`Dataset:${dataset1.name}`)).toBeVisible();
    await expect(page.getByRole("button", { name: "Input" })).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Expected output" }),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Metadata" })).toBeVisible();
  });
});
