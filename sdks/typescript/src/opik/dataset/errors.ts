export class DatasetError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DatasetError";
  }
}

export class DatasetNotFoundError extends DatasetError {
  constructor(datasetName: string) {
    super(`Dataset with name '${datasetName}' not found`);
    this.name = "DatasetNotFoundError";
  }
}

export class DatasetItemUpdateError extends DatasetError {
  constructor() {
    super("All items must have an ID for update operations");
    this.name = "DatasetItemUpdateError";
  }
}
