import { StorageKeysConfig } from "@/v2/pages-shared/datasets/DatasetItemsTab/DatasetItemsTab";

export const DVS_QUERY_PREFIX = "dvs_";

export const SUITE_VIEW_STORAGE_KEYS: StorageKeysConfig = {
  selectedColumnsKey: "test-suite-items-view-selected-columns",
  columnsWidthKey: "test-suite-items-view-columns-width",
  columnsOrderKey: "test-suite-items-view-columns-order",
  dynamicColumnsKey: "test-suite-items-view-dynamic-columns",
  paginationSizeKey: "test-suite-items-view-pagination-size",
  rowHeightKey: "test-suite-items-view-row-height",
};

export const DATASET_VIEW_STORAGE_KEYS: StorageKeysConfig = {
  selectedColumnsKey: "dataset-items-view-selected-columns",
  columnsWidthKey: "dataset-items-view-columns-width",
  columnsOrderKey: "dataset-items-view-columns-order",
  dynamicColumnsKey: "dataset-items-view-dynamic-columns",
  paginationSizeKey: "dataset-items-view-pagination-size",
  rowHeightKey: "dataset-items-view-row-height",
};
