import { datasetErrorMessages } from "./errorMessages";
import { OpikError } from "../BaseError";

const datasetErrorCodes = {
  DATASET_ITEM_MISSING_ID: "DATASET_ITEM_MISSING_ID",
};

export class DatasetItemMissingIdError extends OpikError {
  constructor(index: number) {
    super({
      message: datasetErrorMessages.DATASET_ITEM_MISSING_ID(index),
      code: datasetErrorCodes.DATASET_ITEM_MISSING_ID,
    });
  }
}
