import type { ModelPriceList, ModelPriceRow } from "./types";
import {
  EMPTY_DRAFT,
  changeCounts,
  hasChanges,
  modelPricesReducer,
  statusOf,
  toReplaceRequest,
} from "./draft";
import type { ModelPricesDraft, ModelPricesDraftAction } from "./draft";

const GPT_4O: ModelPriceRow = {
  id: "row-1",
  model: "gpt-4o",
  provider: "openai",
  kind: "fixed",
  input_price: "2",
  output_price: "8",
  note: "Contract rate",
  created_by: "maria",
};

const CLAUDE: ModelPriceRow = {
  id: "row-2",
  model: "claude-sonnet-4-5",
  provider: "anthropic",
  kind: "fixed",
  input_price: "2.4",
  output_price: "12",
};

const LIST: ModelPriceList = { version: 7, rows: [GPT_4O, CLAUDE] };

const run = (...actions: ModelPricesDraftAction[]): ModelPricesDraft =>
  actions.reduce(modelPricesReducer, EMPTY_DRAFT);

const rowByKey = (draft: ModelPricesDraft, key: string) =>
  draft.rows.find((draftRow) => draftRow.key === key);

describe("modelPricesReducer", () => {
  it("loads a list as unchanged rows keyed by id", () => {
    const draft = run({ type: "loaded", list: LIST });

    expect(draft.version).toBe(7);
    expect(draft.rows.map((draftRow) => draftRow.key)).toEqual([
      "row-1",
      "row-2",
    ]);
    expect(draft.rows.map(statusOf)).toEqual(["unchanged", "unchanged"]);
    expect(hasChanges(draft)).toBe(false);
  });

  it("marks an edited price, and treats 2.00 typed over 2 as no change", () => {
    const edited = run(
      { type: "loaded", list: LIST },
      { type: "edited", key: "row-1", changes: { output_price: "7.5" } },
    );
    const retyped = run(
      { type: "loaded", list: LIST },
      { type: "edited", key: "row-1", changes: { input_price: "2.00" } },
    );

    expect(statusOf(rowByKey(edited, "row-1")!)).toBe("edited");
    expect(rowByKey(edited, "row-1")!.row.output_price).toBe("7.5");
    expect(statusOf(rowByKey(retyped, "row-1")!)).toBe("unchanged");
  });

  it("adds new rows on top with their own keys", () => {
    const draft = run(
      { type: "loaded", list: LIST },
      { type: "added", row: { model: "a", kind: "fixed", input_price: "1" } },
      { type: "added", row: { model: "b", kind: "fixed", input_price: "1" } },
    );

    expect(draft.rows.map((draftRow) => draftRow.key)).toEqual([
      "new-2",
      "new-1",
      "row-1",
      "row-2",
    ]);
    expect(changeCounts(draft)).toEqual({ created: 2, updated: 0, deleted: 0 });
  });

  it("drops a removed new row, but only marks a removed saved row", () => {
    const draft = run(
      { type: "loaded", list: LIST },
      { type: "added", row: { model: "a", kind: "fixed", input_price: "1" } },
      { type: "removed", key: "new-1" },
      { type: "removed", key: "row-2" },
    );

    expect(rowByKey(draft, "new-1")).toBeUndefined();
    expect(statusOf(rowByKey(draft, "row-2")!)).toBe("deleted");
    expect(changeCounts(draft)).toEqual({ created: 0, updated: 0, deleted: 1 });
  });

  it("restores a row to what was saved", () => {
    const draft = run(
      { type: "loaded", list: LIST },
      { type: "edited", key: "row-1", changes: { output_price: "1" } },
      { type: "restored", key: "row-1" },
    );

    expect(rowByKey(draft, "row-1")!.row).toEqual(GPT_4O);
    expect(hasChanges(draft)).toBe(false);
  });

  it("discards every change and every new row", () => {
    const draft = run(
      { type: "loaded", list: LIST },
      { type: "edited", key: "row-1", changes: { output_price: "1" } },
      { type: "removed", key: "row-2" },
      { type: "added", row: { model: "a", kind: "fixed", input_price: "1" } },
      { type: "discarded" },
    );

    expect(draft.rows.map((draftRow) => draftRow.row)).toEqual([
      GPT_4O,
      CLAUDE,
    ]);
    expect(draft.version).toBe(7);
  });

  it("builds the replace request from the kept rows, saved rows first, in the shape the API takes", () => {
    const draft = run(
      { type: "loaded", list: LIST },
      { type: "edited", key: "row-1", changes: { output_price: " 7.50 " } },
      { type: "removed", key: "row-2" },
      {
        type: "added",
        row: {
          model: "bifrost/gpt-4o",
          provider: "",
          kind: "alias",
          input_price: "9",
          alias_of: { model: "gpt-4o", provider: "openai" },
        },
      },
    );

    const { request, keys } = toReplaceRequest(draft);

    expect(keys).toEqual(["row-1", "new-1"]);
    expect(request).toEqual({
      expected_version: 7,
      rows: [
        {
          model: "gpt-4o",
          provider: "openai",
          kind: "fixed",
          input_price: "2",
          output_price: "7.5",
          note: "Contract rate",
        },
        {
          model: "bifrost/gpt-4o",
          provider: "",
          kind: "alias",
          alias_of: { model: "gpt-4o", provider: "openai" },
        },
      ],
    });
  });

  it("places 422 problems on the rows that were sent, by position", () => {
    const loaded = run(
      { type: "loaded", list: LIST },
      { type: "added", row: { model: "x", kind: "fixed", input_price: "1" } },
    );
    const { keys } = toReplaceRequest(loaded);

    const draft = modelPricesReducer(loaded, {
      type: "problemsReceived",
      keys,
      errors: [
        { row: 2, field: "input_price", code: "not_a_number", message: "bad" },
        { row: null, field: null, code: "too_many_rows", message: "many" },
      ],
      warnings: [{ row: 0, field: "model", code: "no_base", message: "w" }],
    });

    expect(keys).toEqual(["row-1", "row-2", "new-1"]);
    expect(draft.errors).toEqual({
      "new-1": [
        { row: 2, field: "input_price", code: "not_a_number", message: "bad" },
      ],
    });
    expect(draft.warnings["row-1"]).toHaveLength(1);
    expect(draft.listErrors.map((problem) => problem.code)).toEqual([
      "too_many_rows",
    ]);
  });

  it("clears a row's problems once it is edited again", () => {
    const draft = run(
      { type: "loaded", list: LIST },
      {
        type: "problemsReceived",
        keys: ["row-1", "row-2"],
        errors: [{ row: 0, field: "model", code: "x", message: "x" }],
        warnings: [],
      },
      { type: "edited", key: "row-1", changes: { model: "gpt-4o-mini" } },
    );

    expect(draft.errors).toEqual({});
  });

  it("resets to the saved list after a save, without repeating the rows just added", () => {
    const afterSave: ModelPriceList = {
      version: 8,
      rows: [
        GPT_4O,
        { id: "row-9", model: "a", kind: "fixed", input_price: "1" },
      ],
    };
    const draft = run(
      { type: "loaded", list: LIST },
      { type: "added", row: { model: "A", kind: "fixed", input_price: "1.0" } },
      { type: "removed", key: "row-2" },
      { type: "reset", list: afterSave },
    );

    expect(draft.rows.map((draftRow) => draftRow.key)).toEqual([
      "row-1",
      "row-9",
    ]);
    expect(hasChanges(draft)).toBe(false);
    expect(draft.version).toBe(8);
  });

  describe("reloading while there are changes (after a 409)", () => {
    const theirs: ModelPriceList = {
      version: 8,
      rows: [
        { ...GPT_4O, input_price: "1.9" },
        CLAUDE,
        {
          id: "row-3",
          model: "gpt-5-mini",
          provider: "openai",
          kind: "fixed",
          input_price: "0",
        },
      ],
    };

    it("lays my edits over their version and flags rows they also changed", () => {
      const draft = run(
        { type: "loaded", list: LIST },
        { type: "edited", key: "row-1", changes: { output_price: "7.5" } },
        { type: "loaded", list: theirs },
      );

      expect(draft.version).toBe(8);
      expect(rowByKey(draft, "row-1")!.row).toMatchObject({
        input_price: "1.9",
        output_price: "7.5",
      });
      expect(statusOf(rowByKey(draft, "row-3")!)).toBe("unchanged");
      expect(draft.changedElsewhere).toEqual(["row-1"]);
    });

    it("keeps their change to a field I left alone when I edited the row in the dialog", () => {
      const draft = run(
        { type: "loaded", list: LIST },
        {
          type: "edited",
          key: "row-1",
          changes: { ...GPT_4O, note: "Renewed in 2026" },
        },
        { type: "loaded", list: theirs },
      );

      expect(rowByKey(draft, "row-1")!.changes).toEqual({
        note: "Renewed in 2026",
      });
      expect(rowByKey(draft, "row-1")!.row).toMatchObject({
        input_price: "1.9",
        output_price: "8",
        note: "Renewed in 2026",
      });
    });

    it("keeps my new rows and my deletions", () => {
      const draft = run(
        { type: "loaded", list: LIST },
        { type: "added", row: { model: "a", kind: "fixed", input_price: "1" } },
        { type: "removed", key: "row-2" },
        { type: "loaded", list: theirs },
      );

      expect(statusOf(rowByKey(draft, "new-1")!)).toBe("new");
      expect(statusOf(rowByKey(draft, "row-2")!)).toBe("deleted");
      expect(draft.changedElsewhere).toEqual([]);
    });

    it("keeps an edit to a row they deleted, as a new row", () => {
      const draft = run(
        { type: "loaded", list: LIST },
        { type: "edited", key: "row-2", changes: { output_price: "11" } },
        { type: "loaded", list: { version: 8, rows: [GPT_4O] } },
      );

      const kept = rowByKey(draft, "row-2")!;
      expect(statusOf(kept)).toBe("new");
      expect(kept.row).toMatchObject({
        model: "claude-sonnet-4-5",
        output_price: "11",
      });
      expect(kept.row.id).toBeUndefined();
      expect(draft.changedElsewhere).toEqual(["row-2"]);
    });

    it("doesn't flag a row whose newer version already matches my edit", () => {
      const draft = run(
        { type: "loaded", list: LIST },
        { type: "edited", key: "row-1", changes: { input_price: "1.9" } },
        { type: "loaded", list: theirs },
      );

      expect(statusOf(rowByKey(draft, "row-1")!)).toBe("unchanged");
      expect(draft.changedElsewhere).toEqual([]);
    });

    it("replaces everything when there are no changes", () => {
      const draft = run(
        { type: "loaded", list: LIST },
        { type: "loaded", list: theirs },
      );

      expect(draft.rows).toHaveLength(3);
      expect(hasChanges(draft)).toBe(false);
      expect(draft.changedElsewhere).toEqual([]);
    });
  });
});
