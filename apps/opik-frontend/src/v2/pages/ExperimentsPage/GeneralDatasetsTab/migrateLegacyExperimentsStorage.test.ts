import { beforeEach, describe, expect, it } from "vitest";

import { migrateLegacyExperimentsStorage } from "./migrateLegacyExperimentsStorage";

const PREFIX = "experiments-test-suites";

const migrate = () =>
  migrateLegacyExperimentsStorage({
    storageKeyPrefix: PREFIX,
    excludedColumnIds: ["feedback_scores"],
    addedColumnIds: ["dataset_version"],
  });

const setLegacy = (suffix: string, value: unknown) =>
  localStorage.setItem(`experiments-${suffix}`, JSON.stringify(value));

const read = (suffix: string) =>
  JSON.parse(localStorage.getItem(`${PREFIX}-${suffix}`) ?? "null");

describe("migrateLegacyExperimentsStorage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("drops excluded columns and adds the new ones to the selection", () => {
    setLegacy("selected-columns-v2", ["name", "pass_rate", "feedback_scores"]);

    migrate();

    expect(read("selected-columns-v2")).toEqual([
      "name",
      "pass_rate",
      "dataset_version",
    ]);
  });

  it("places added columns right after Name in the saved order", () => {
    setLegacy("columns-order", ["id", "name", "feedback_scores", "created_at"]);

    migrate();

    expect(read("columns-order")).toEqual([
      "id",
      "name",
      "dataset_version",
      "created_at",
    ]);
  });

  it("drops a sort on an excluded column", () => {
    setLegacy("columns-sort", [{ id: "feedback_scores", desc: true }]);

    migrate();

    expect(read("columns-sort")).toEqual([]);
  });

  it("copies groups, widths, page size and per-project pins as-is", () => {
    setLegacy("columns-groups", []);
    setLegacy("columns-width", { name: 300 });
    setLegacy("pagination-size", 50);
    setLegacy("pinned-experiments-project-1", ["exp-1"]);

    migrate();

    expect(read("columns-groups")).toEqual([]);
    expect(read("columns-width")).toEqual({ name: 300 });
    expect(read("pagination-size")).toBe(50);
    expect(read("pinned-experiments-project-1")).toEqual(["exp-1"]);
  });

  it("never overwrites a choice already saved for the tab", () => {
    setLegacy("columns-width", { name: 300 });
    localStorage.setItem(`${PREFIX}-columns-width`, JSON.stringify({}));

    migrate();

    expect(read("columns-width")).toEqual({});
  });

  it("leaves the tab on its defaults when nothing was saved or the value is corrupt", () => {
    localStorage.setItem("experiments-selected-columns-v2", "{not json");

    migrate();

    expect(localStorage.getItem(`${PREFIX}-selected-columns-v2`)).toBeNull();
    expect(localStorage.getItem(`${PREFIX}-columns-order`)).toBeNull();
  });
});
