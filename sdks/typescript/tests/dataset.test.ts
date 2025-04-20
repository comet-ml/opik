import { Opik } from "opik";
import { Dataset } from "@/dataset/Dataset";
import { vi, describe, it, expect, beforeEach, afterEach, MockInstance } from "vitest";

async function mockAPIPromise<T>() {
  return {} as T;
}

describe("OpikClient Dataset Management", () => {
  let client: Opik;
  let createDatasetSpy: MockInstance<typeof client.api.datasets.createDataset>;
  let getDatasetSpy: MockInstance<typeof client.api.datasets.getDatasetByIdentifier>;
  let createOrUpdateDatasetItemsSpy: MockInstance<typeof client.api.datasets.createOrUpdateDatasetItems>;
  let getDatasetItemsSpy: MockInstance<typeof client.api.datasets.getDatasetItems>;
  let deleteDatasetSpy: MockInstance<typeof client.api.datasets.deleteDatasetByName>;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    createDatasetSpy = vi
      .spyOn(client.api.datasets, "createDataset")
      .mockImplementation(mockAPIPromise);

    getDatasetSpy = vi
      .spyOn(client.api.datasets, "getDatasetByIdentifier")
      .mockImplementation(mockAPIPromise);

    getDatasetItemsSpy = vi
      .spyOn(client.api.datasets, "getDatasetItems")
      .mockImplementation(mockAPIPromise);
    
    createOrUpdateDatasetItemsSpy = vi
      .spyOn(client.api.datasets, "createOrUpdateDatasetItems")
      .mockImplementation(mockAPIPromise);

    deleteDatasetSpy = vi
      .spyOn(client.api.datasets, "deleteDatasetByName")
      .mockImplementation(mockAPIPromise);
  });

  afterEach(() => {
    createDatasetSpy.mockRestore();
    getDatasetSpy.mockRestore();
    getDatasetItemsSpy.mockRestore();
    createOrUpdateDatasetItemsSpy.mockRestore();
    deleteDatasetSpy.mockRestore();
  });

  it("should create a new dataset", async () => {
    const dataset = await client.createDataset("Test Dataset", "A test dataset");

    expect(createDatasetSpy).toHaveBeenCalledTimes(1);
    expect(dataset).toBeInstanceOf(Dataset);
    expect(dataset.name).toBe("Test Dataset");
  });

  it("should retrieve an existing dataset", async () => {
    const mockDataset = { id: "123", name: "Test Dataset", description: "A test dataset" };
    getDatasetSpy.mockResolvedValueOnce(mockDataset);

    const dataset = await client.getDataset("Test Dataset");
    expect(getDatasetSpy).toHaveBeenCalledTimes(1);
    expect(dataset).toBeInstanceOf(Dataset);
    expect(dataset.id).toBe("123");
  });

  it("should delete a dataset", async () => {
    await client.deleteDataset("Test Dataset");
    expect(deleteDatasetSpy).toHaveBeenCalledTimes(1);
  });

  it("should insert items into a dataset", async () => {
    const dataset = new Dataset("123", "Test Dataset", "A test dataset", client.api);
    const items = [{ key: "valueas" }, { key: "another valueass" }];
    await dataset.insert(items);

    expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(1);
  });
});
