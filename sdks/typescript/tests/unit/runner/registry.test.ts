import {
  register,
  getAll,
  extractParams,
  onRegister,
  type RegistryEntry,
} from "@/runner/registry";

// Registry is module-level state, so tests share it.
// We test additive behavior rather than assuming a clean slate.

describe("runner registry", () => {
  describe("register and getAll", () => {
    it("registers an entry and retrieves it", () => {
      const entry: RegistryEntry = {
        func: () => "hello",
        name: "test-agent-reg",
        project: "default",
        params: [{ name: "msg", type: "string" }],
        docstring: "A test agent",
      };

      register(entry);
      const all = getAll();
      expect(all.get("test-agent-reg")).toEqual(entry);
    });

    it("returns a copy of the registry (mutations don't affect internal state)", () => {
      const entry: RegistryEntry = {
        func: () => "x",
        name: "test-agent-copy",
        project: "default",
        params: [],
        docstring: "",
      };
      register(entry);

      const copy = getAll();
      copy.delete("test-agent-copy");

      expect(getAll().has("test-agent-copy")).toBe(true);
    });

    it("overwrites an entry with the same name", () => {
      const first: RegistryEntry = {
        func: () => "v1",
        name: "test-agent-overwrite",
        project: "default",
        params: [],
        docstring: "v1",
      };
      const second: RegistryEntry = {
        func: () => "v2",
        name: "test-agent-overwrite",
        project: "default",
        params: [],
        docstring: "v2",
      };

      register(first);
      register(second);

      const entry = getAll().get("test-agent-overwrite");
      expect(entry?.docstring).toBe("v2");
    });
  });

  describe("onRegister listener", () => {
    it("calls listener when a new entry is registered", () => {
      const names: string[] = [];
      onRegister((name) => names.push(name));

      register({
        func: () => "listener-test",
        name: "test-agent-listener",
        project: "default",
        params: [],
        docstring: "",
      });

      expect(names).toContain("test-agent-listener");
    });
  });

  describe("extractParams", () => {
    it("extracts params from a regular function", () => {
      function myFunc(a: string, b: number) {
        return a + b;
      }
      const params = extractParams(myFunc);
      expect(params).toEqual([
        { name: "a", type: "string" },
        { name: "b", type: "string" },
      ]);
    });

    it("extracts params from an arrow function", () => {
      const fn = (query: string, context: string) => query + context;
      const params = extractParams(fn);
      expect(params).toEqual([
        { name: "query", type: "string" },
        { name: "context", type: "string" },
      ]);
    });

    it("extracts params from an async arrow function", () => {
      const fn = async (input: string) => input;
      const params = extractParams(fn);
      expect(params).toEqual([{ name: "input", type: "string" }]);
    });

    it("extracts params from an async function", () => {
      async function process(data: string, count: number) {
        return data.repeat(count);
      }
      const params = extractParams(process);
      expect(params).toEqual([
        { name: "data", type: "string" },
        { name: "count", type: "string" },
      ]);
    });

    it("returns empty array for no-arg function", () => {
      const fn = () => 42;
      expect(extractParams(fn)).toEqual([]);
    });

    it("handles single-arg arrow without parens", () => {
      const fn = (x: string) => x;
      const params = extractParams(fn);
      expect(params).toEqual([{ name: "x", type: "string" }]);
    });

    it("strips default values from param names", () => {
      // Default values are visible in fn.toString()
      function fn(a: string, b = "hello") {
        return a + b;
      }
      const params = extractParams(fn);
      expect(params[0].name).toBe("a");
      expect(params[1].name).toBe("b");
    });
  });
});
