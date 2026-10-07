import { beforeEach, describe, expect, it, vi } from "vitest";

import usePlaygroundStore, {
  beginExperimentRun,
  getExperimentNamesForPrompts,
} from "@/store/PlaygroundStore";

const DATASET_ID = "dataset-1";

const state = () => usePlaygroundStore.getState();

const setUp = (experimentName: string | null, promptIds = ["p1", "p2"]) =>
  usePlaygroundStore.setState({
    promptIds,
    experimentName,
    lastSuggestedExperimentName: null,
    lastRun: null,
  });

const runAll = (ids: string[], datasetId = DATASET_ID) => {
  const register = beginExperimentRun(datasetId);
  const names = getExperimentNamesForPrompts(state().promptIds);
  const map = Object.fromEntries(
    state().promptIds.map((promptId, i) => [promptId, ids[i]]),
  );
  register(
    ids.map((id) => ({ id })),
    map,
  );
  return Object.values(names);
};

describe("PlaygroundStore last run", () => {
  beforeEach(() => {
    localStorage.clear();
    setUp(null);
  });

  it("keeps the run's name in the box and remembers which experiments it made", () => {
    setUp("foo");

    expect(runAll(["e1", "e2"])).toEqual(["foo_a", "foo_b"]);

    expect(state().experimentName).toBe("foo");
    expect(state().lastRun).toEqual({
      name: "foo",
      datasetId: DATASET_ID,
      experiments: [
        { id: "e1", index: 0 },
        { id: "e2", index: 1 },
      ],
    });
  });

  it("gives a re-run with the same name the next suffix", () => {
    setUp("foo");
    runAll(["e1", "e2"]);

    expect(runAll(["e3", "e4"])).toEqual(["foo_02_a", "foo_02_b"]);
    expect(state().experimentName).toBe("foo_02");
    expect(runAll(["e5", "e6"])).toEqual(["foo_03_a", "foo_03_b"]);
    expect(state().lastRun?.experiments.map((e) => e.id)).toEqual(["e5", "e6"]);
  });

  it("leaves an auto-named run unnamed and still remembers its experiments", () => {
    expect(runAll(["e1", "e2"])).toEqual([undefined, undefined]);
    expect(runAll(["e3", "e4"])).toEqual([undefined, undefined]);

    expect(state().experimentName).toBeNull();
    expect(state().lastRun).toMatchObject({
      name: null,
      experiments: [{ id: "e3" }, { id: "e4" }],
    });
  });

  it("does not suffix the first run on a dataset the last run was not on", () => {
    setUp("foo");
    runAll(["e1", "e2"], "other-dataset");

    expect(runAll(["e3", "e4"])).toEqual(["foo_a", "foo_b"]);
  });

  it("collects experiments reported one at a time without duplicates", () => {
    setUp("foo");
    const register = beginExperimentRun(DATASET_ID);
    const map = { p1: "e1", p2: "e2" };

    register([{ id: "e1" }], map);
    register([{ id: "e1" }, { id: "e2" }], map);

    expect(state().lastRun?.experiments).toEqual([
      { id: "e1", index: 0 },
      { id: "e2", index: 1 },
    ]);
  });

  it("only remembers experiments that were created", () => {
    setUp("foo");
    beginExperimentRun(DATASET_ID)([{ id: "e2" }], { p1: "e1", p2: "e2" });

    expect(state().lastRun?.experiments).toEqual([{ id: "e2", index: 1 }]);
  });

  it("keeps the suffix a prompt had when the run started", () => {
    setUp("foo");
    const register = beginExperimentRun(DATASET_ID);
    usePlaygroundStore.setState({ promptIds: ["p2"] });
    register([{ id: "e2" }], { p2: "e2" });

    expect(state().lastRun?.experiments).toEqual([{ id: "e2", index: 1 }]);
  });

  it("joins prompts that were started side by side into one run", () => {
    setUp("foo");
    runAll(["e1", "e2"]);
    const registerA = beginExperimentRun(DATASET_ID);
    const registerB = beginExperimentRun(DATASET_ID);

    registerA([{ id: "e3" }], { p1: "e3" });
    registerB([{ id: "e4" }], { p2: "e4" });

    expect(state().lastRun).toMatchObject({
      name: "foo_02",
      experiments: [
        { id: "e3", index: 0 },
        { id: "e4", index: 1 },
      ],
    });
  });

  it("ignores experiments reported after the box was renamed", () => {
    setUp("foo");
    const register = beginExperimentRun(DATASET_ID);
    const map = { p1: "e1", p2: "e2" };
    register([{ id: "e1" }], map);

    state().applyLastRunRename("bar", ["e1"]);
    register([{ id: "e1" }, { id: "e2" }], map);

    expect(state().lastRun).toEqual({
      name: "bar",
      datasetId: DATASET_ID,
      experiments: [{ id: "e1", index: 0 }],
    });
  });

  it("ignores experiments reported after the name was changed directly", () => {
    setUp("foo");
    const register = beginExperimentRun(DATASET_ID);

    state().setExperimentName("bar");
    register([{ id: "e1" }], { p1: "e1" });

    expect(state().lastRun).toBeNull();
  });

  describe("applyLastRunRename", () => {
    it("moves the box and the run to the new name", () => {
      setUp("foo");
      runAll(["e1", "e2"]);

      state().applyLastRunRename("bar", ["e1", "e2"]);

      expect(state().experimentName).toBe("bar");
      expect(state().lastRun?.name).toBe("bar");
      expect(runAll(["e3", "e4"])).toEqual(["bar_02_a", "bar_02_b"]);
    });

    it("stops tracking experiments that kept their old name", () => {
      setUp("foo");
      runAll(["e1", "e2"]);

      state().applyLastRunRename("bar", ["e2"]);

      expect(state().lastRun?.experiments).toEqual([{ id: "e2", index: 1 }]);
    });

    it("changes nothing when no experiment was renamed", () => {
      setUp("foo");
      runAll(["e1", "e2"]);
      const before = state().lastRun;

      state().applyLastRunRename("bar", []);

      expect(state().experimentName).toBe("foo");
      expect(state().lastRun).toBe(before);
    });

    it("leaves the box alone when a newer run started meanwhile", () => {
      setUp("foo");
      runAll(["e1", "e2"]);
      runAll(["e3", "e4"]);

      state().applyLastRunRename("bar", ["e1", "e2"]);

      expect(state().experimentName).toBe("foo_02");
    });
  });

  it("forgets the last run when the name is set directly", () => {
    setUp("foo");
    runAll(["e1", "e2"]);

    state().setExperimentName(null);

    expect(state().lastRun).toBeNull();
    expect(runAll(["e3", "e4"])).toEqual([undefined, undefined]);
  });

  it("keeps the last run across a reload", async () => {
    setUp("foo");
    runAll(["e1", "e2"]);

    vi.resetModules();
    const reloaded = await import("@/store/PlaygroundStore");

    expect(reloaded.default.getState().experimentName).toBe("foo");
    expect(reloaded.default.getState().lastRun?.experiments).toHaveLength(2);
    reloaded.beginExperimentRun(DATASET_ID);
    expect(reloaded.default.getState().experimentName).toBe("foo_02");
  });
});
