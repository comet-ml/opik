import { datasetErrorMessages } from "./errorMessages";
import { OpikError } from "../BaseError";

const datasetErrorCodes = {
  DATASET_ITEM_MISSING_ID: "DATASET_ITEM_MISSING_ID",
  DATASET_VERSION_NOT_FOUND: "DATASET_VERSION_NOT_FOUND",
};

export class DatasetItemMissingIdError extends OpikError {
  constructor(index: number) {
    super({
      message: datasetErrorMessages.DATASET_ITEM_MISSING_ID(index),
      code: datasetErrorCodes.DATASET_ITEM_MISSING_ID,
    });
  }
}

export class DatasetVersionNotFoundError extends OpikError {
  constructor(versionName: string, datasetName: string) {
    super({
      message: datasetErrorMessages.DATASET_VERSION_NOT_FOUND(
        versionName,
        datasetName
      ),
      code: datasetErrorCodes.DATASET_VERSION_NOT_FOUND,
    });
  }
}
