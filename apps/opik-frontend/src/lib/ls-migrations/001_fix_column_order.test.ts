import { beforeEach, describe, expect, it } from "vitest";
import { migration001 } from "./001_fix_column_order";

function setOrder(key: string, value: string[]) {
  localStorage.setItem(`${key}-columns-order`, JSON.stringify(value));
}

function setSelected(key: string, value: string[]) {
  localStorage.setItem(`${key}-selected-columns-v2`, JSON.stringify(value));
}

function setSelectedV1(key: string, value: string[]) {
  localStorage.setItem(`${key}-selected-columns`, JSON.stringify(value));
}

function getOrder(key: string): string[] | null {
  const raw = localStorage.getItem(`${key}-columns-order`);
  return raw ? JSON.parse(raw) : null;
}

describe("001_fix_column_order", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe("Group A (name only)", () => {
    const prefix = "projects";

    it("inserts name at position 0 when missing from order", () => {
      setOrder(prefix, ["last_updated_at", "trace_count", "duration.p50"]);
      setSelected(prefix, ["name", "last_updated_at", "trace_count"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual([
        "name",
        "last_updated_at",
        "trace_count",
        "duration.p50",
      ]);
    });

    it("does not insert name when it is not visible", () => {
      setOrder(prefix, ["last_updated_at", "trace_count"]);
      setSelected(prefix, ["last_updated_at", "trace_count"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual(["last_updated_at", "trace_count"]);
    });

    it("moves name from last position to front", () => {
      setOrder(prefix, ["last_updated_at", "trace_count", "name"]);
      setSelected(prefix, ["name", "last_updated_at", "trace_count"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual([
        "name",
        "last_updated_at",
        "trace_count",
      ]);
    });

    it("does NOT move name if it is not in the last position", () => {
      setOrder(prefix, ["last_updated_at", "name", "trace_count"]);
      setSelected(prefix, ["name", "last_updated_at", "trace_count"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual([
        "last_updated_at",
        "name",
        "trace_count",
      ]);
    });

    it("works for all Group A keys", () => {
      const allGroupA = [
        "projects",
        "workspace-annotation-queues",
        "annotation-queues",
        "workspace-rules",
        "project-rules",
        "datasets",
        "dashboards",
        "prompts",
        "alerts",
        "feedback-definitions",
        "experiments",
        "prompt-experiments",
      ];

      for (const key of allGroupA) {
        setOrder(key, ["col_a", "col_b"]);
        setSelected(key, ["name", "col_a", "col_b"]);
      }

      migration001.run();

      for (const key of allGroupA) {
        expect(getOrder(key)).toEqual(["name", "col_a", "col_b"]);
      }
    });
  });

  describe("Group B (id only)", () => {
    const prefix = "traces";

    it("inserts id at position 0 when missing", () => {
      setOrder(prefix, ["input", "output", "duration"]);
      setSelected(prefix, ["id", "input", "output"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual(["id", "input", "output", "duration"]);
    });

    it("does not insert id when it is not visible", () => {
      setOrder(prefix, ["input", "output"]);
      setSelected(prefix, ["input", "output"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual(["input", "output"]);
    });

    it("moves id from last position to front", () => {
      setOrder(prefix, ["input", "output", "id"]);
      setSelected(prefix, ["id", "input", "output"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual(["id", "input", "output"]);
    });
  });

  describe("optimization-experiments (name only)", () => {
    const prefix = "optimization-experiments";

    it("inserts name at front when missing", () => {
      setOrder(prefix, ["col_a", "col_b", "col_c"]);
      setSelected(prefix, ["name", "col_a", "col_b"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual(["name", "col_a", "col_b", "col_c"]);
    });

    it("does not insert name when not visible", () => {
      setOrder(prefix, ["col_a", "col_b", "col_c"]);
      setSelected(prefix, ["col_a", "col_b"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual(["col_a", "col_b", "col_c"]);
    });
  });

  describe("edge cases", () => {
    const prefix = "projects";

    it("skips keys with no stored value", () => {
      // No orderKey set
      setSelected(prefix, ["name", "col_a"]);

      migration001.run();

      expect(getOrder(prefix)).toBeNull();
    });

    it("skips keys with empty arrays", () => {
      localStorage.setItem(`${prefix}-columns-order`, JSON.stringify([]));
      setSelected(prefix, ["name"]);

      migration001.run();

      expect(
        JSON.parse(localStorage.getItem(`${prefix}-columns-order`)!),
      ).toEqual([]);
    });

    it("skips keys with invalid JSON", () => {
      localStorage.setItem(`${prefix}-columns-order`, "not-json");
      setSelected(prefix, ["name"]);

      migration001.run();

      expect(localStorage.getItem(`${prefix}-columns-order`)).toBe("not-json");
    });

    it("does not modify keys that already have columns in correct positions", () => {
      setOrder(prefix, ["name", "last_updated_at", "trace_count"]);
      setSelected(prefix, ["name", "last_updated_at", "trace_count"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual([
        "name",
        "last_updated_at",
        "trace_count",
      ]);
    });

    it("falls back to v1 selectedColumns key when v2 does not exist", () => {
      setOrder(prefix, ["last_updated_at", "trace_count"]);
      setSelectedV1(prefix, ["name", "last_updated_at", "trace_count"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual([
        "name",
        "last_updated_at",
        "trace_count",
      ]);
    });

    it("skips when no selectedColumns key exists", () => {
      setOrder(prefix, ["last_updated_at", "trace_count"]);

      migration001.run();

      expect(getOrder(prefix)).toEqual(["last_updated_at", "trace_count"]);
    });
  });
});
