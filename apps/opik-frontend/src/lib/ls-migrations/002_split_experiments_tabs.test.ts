import { beforeEach, describe, expect, it } from "vitest";

import { migration002 } from "./002_split_experiments_tabs";

const SUITES = "experiments-test-suites";
const DATASETS = "experiments-datasets";

const setLegacy = (suffix: string, value: unknown) =>
  localStorage.setItem(`experiments-${suffix}`, JSON.stringify(value));

const read = (prefix: string, suffix: string) =>
  JSON.parse(localStorage.getItem(`${prefix}-${suffix}`) ?? "null");

describe("002_split_experiments_tabs", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("drops each tab's hidden columns and adds the version column to the selection", () => {
    setLegacy("selected-columns-v2", [
      "name",
      "pass_rate",
      "feedback_scores",
      "feedback_scores.accuracy",
    ]);

    migration002.run();

    expect(read(SUITES, "selected-columns-v2")).toEqual([
      "name",
      "pass_rate",
      "dataset_version",
    ]);
    expect(read(DATASETS, "selected-columns-v2")).toEqual([
      "name",
      "feedback_scores",
      "feedback_scores.accuracy",
      "dataset_version",
    ]);
  });

  it("falls back to the v1 selection, with Name added, when there is no v2 key", () => {
    setLegacy("selected-columns", ["created_at", "feedback_scores"]);

    migration002.run();

    expect(read(SUITES, "selected-columns-v2")).toEqual([
      "name",
      "created_at",
      "dataset_version",
    ]);
  });

  it("places the version column right after Name in the saved order", () => {
    setLegacy("columns-order", ["id", "name", "feedback_scores", "created_at"]);

    migration002.run();

    expect(read(SUITES, "columns-order")).toEqual([
      "id",
      "name",
      "dataset_version",
      "created_at",
    ]);
  });

  it("drops a sort on any column the tab does not show, including per-score columns", () => {
    setLegacy("columns-sort", [{ id: "experiment_scores.x", desc: true }]);

    migration002.run();

    expect(read(SUITES, "columns-sort")).toEqual([]);
    expect(read(DATASETS, "columns-sort")).toEqual([
      { id: "experiment_scores.x", desc: true },
    ]);
  });

  it("copies groups, widths, page size and per-project pins as-is", () => {
    setLegacy("columns-groups", []);
    setLegacy("columns-width", { name: 300 });
    setLegacy("pagination-size", 50);
    setLegacy("pinned-experiments-project-1", ["exp-1"]);

    migration002.run();

    [SUITES, DATASETS].forEach((prefix) => {
      expect(read(prefix, "columns-groups")).toEqual([]);
      expect(read(prefix, "columns-width")).toEqual({ name: 300 });
      expect(read(prefix, "pagination-size")).toBe(50);
      expect(read(prefix, "pinned-experiments-project-1")).toEqual(["exp-1"]);
    });
  });

  it("never overwrites a choice already saved for the tab", () => {
    setLegacy("columns-width", { name: 300 });
    localStorage.setItem(`${SUITES}-columns-width`, JSON.stringify({}));

    migration002.run();

    expect(read(SUITES, "columns-width")).toEqual({});
  });

  it("skips corrupt or wrongly shaped values and still migrates the rest", () => {
    localStorage.setItem("experiments-selected-columns-v2", "{not json");
    setLegacy("columns-order", {});
    setLegacy("columns-sort", [null]);
    setLegacy("columns-width", { name: 300 });

    expect(migration002.run).not.toThrow();

    expect(localStorage.getItem(`${SUITES}-selected-columns-v2`)).toBeNull();
    expect(localStorage.getItem(`${SUITES}-columns-order`)).toBeNull();
    expect(localStorage.getItem(`${SUITES}-columns-sort`)).toBeNull();
    expect(read(SUITES, "columns-width")).toEqual({ name: 300 });
  });

  it("skips a list with any invalid member instead of keeping only the valid ones", () => {
    setLegacy("selected-columns-v2", ["name", null]);
    setLegacy("columns-order", [1]);
    setLegacy("columns-sort", [{}]);

    migration002.run();

    expect(localStorage.getItem(`${SUITES}-selected-columns-v2`)).toBeNull();
    expect(localStorage.getItem(`${SUITES}-columns-order`)).toBeNull();
    expect(localStorage.getItem(`${SUITES}-columns-sort`)).toBeNull();
  });

  it("puts the version column at the end when Name is not in the saved order", () => {
    setLegacy("columns-order", ["created_at", "trace_count"]);

    migration002.run();

    expect(read(SUITES, "columns-order")).toEqual([
      "created_at",
      "trace_count",
      "dataset_version",
    ]);
  });
});
