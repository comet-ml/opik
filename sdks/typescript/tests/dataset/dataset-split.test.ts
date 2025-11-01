import { afterEach, describe, expect, it, vi } from "vitest";

import {
  Dataset,
  DatasetItemData,
  DatasetSplitOptions,
} from "@/dataset/Dataset";
import type { OpikClient } from "@/client/Client";

describe("Dataset.getSplit", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function makeDataset<T extends DatasetItemData>(items: (T & { id: string })[]) {
    const stubClient: Pick<OpikClient, "datasetBatchQueue" | "api"> = {
      datasetBatchQueue: { flush: vi.fn() },
      api: { datasets: {} },
    };

    const dataset = new Dataset<T>({ name: "test", id: "ds" }, stubClient as OpikClient);
    vi.spyOn(dataset, "getItems").mockResolvedValue(items);
    return dataset;
  }

  it("splits by ratio deterministically", async () => {
    const items = Array.from({ length: 10 }, (_, idx) => ({
      id: `${idx}`,
      input: `q${idx}`,
    }));

    const dataset = makeDataset(items);
    const split = await dataset.getSplit({ validationRatio: 0.3, seed: 123 });

    expect(split.validation).toHaveLength(3);
    expect(split.train).toHaveLength(7);
    const trainIds = new Set(split.train.map((item) => item.id));
    const validationIds = new Set(split.validation.map((item) => item.id));
    for (const id of validationIds) {
      expect(trainIds.has(id)).toBe(false);
    }
  });

  it("returns empty validation when dataset is too small for ratio", async () => {
    const dataset = makeDataset([{ id: "only", value: "sample" }]);
    const split = await dataset.getSplit({ validationRatio: 0.5, seed: 0 });
    expect(split.validation).toHaveLength(0);
    expect(split.train).toHaveLength(1);
  });

  it("respects splitField metadata labels", async () => {
    const items = [
      { id: "1", metadata: { split: "train" } },
      { id: "2", metadata: { split: "validation" } },
      { id: "3", metadata: { split: "train" } },
    ];

    const dataset = makeDataset(items);
    const split = await dataset.getSplit({ splitField: "split" });

    expect(split.validation.map((item) => item.id)).toEqual(["2"]);
    expect(new Set(split.train.map((item) => item.id))).toEqual(
      new Set(["1", "3"])
    );
  });

  it("splits by explicit validation ids", async () => {
    const items = [
      { id: "a", value: 1 },
      { id: "b", value: 2 },
      { id: "c", value: 3 },
    ];
    const dataset = makeDataset(items);

    const split = await dataset.getSplit({ validationIds: ["b"] });

    expect(split.validation.map((item) => item.id)).toEqual(["b"]);
    expect(new Set(split.train.map((item) => item.id))).toEqual(
      new Set(["a", "c"])
    );
  });

  it("throws when multiple split strategies provided", async () => {
    const dataset = makeDataset([{ id: "x" }]);

    await expect(
      dataset.getSplit({
        splitField: "split",
        validationRatio: 0.2,
      } as DatasetSplitOptions)
    ).rejects.toThrow(/Only one validation split strategy/);
  });

  it("uses validation dataset when provided", async () => {
    const trainDataset = makeDataset([
      { id: "t1", val: 1 },
      { id: "t2", val: 2 },
    ]);
    const validationDataset = makeDataset([
      { id: "v1", val: 3 },
      { id: "v2", val: 4 },
    ]);

    const split = await trainDataset.getSplit({
      validationDataset,
      limit: 10,
    });

    expect(split.train.map((item) => item.id)).toEqual(["t1", "t2"]);
    expect(split.validation.map((item) => item.id)).toEqual(["v1", "v2"]);
  });
});
