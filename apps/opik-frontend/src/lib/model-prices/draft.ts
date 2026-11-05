import type {
  ModelPriceList,
  ModelPriceProblem,
  ModelPriceReplace,
  ModelPriceRow,
} from "./types";
import { isSameRow, toEditableRow } from "./rows";

export type DraftRowStatus = "unchanged" | "edited" | "new" | "deleted";

export interface DraftRow {
  key: string;
  saved?: ModelPriceRow;
  changes: Partial<ModelPriceRow>;
  row: ModelPriceRow;
  deleted: boolean;
}

export interface ModelPricesDraft {
  version: number;
  rows: DraftRow[];
  nextKey: number;
  errors: Record<string, ModelPriceProblem[]>;
  warnings: Record<string, ModelPriceProblem[]>;
  listErrors: ModelPriceProblem[];
  changedElsewhere: string[];
}

export type ModelPricesDraftAction =
  | { type: "loaded"; list: ModelPriceList }
  | { type: "reset"; list: ModelPriceList }
  | { type: "edited"; key: string; changes: Partial<ModelPriceRow> }
  | { type: "added"; row: ModelPriceRow }
  | { type: "removed"; key: string }
  | { type: "restored"; key: string }
  | { type: "discarded" }
  | {
      type: "problemsReceived";
      keys: string[];
      errors: ModelPriceProblem[];
      warnings: ModelPriceProblem[];
    };

export const EMPTY_DRAFT: ModelPricesDraft = {
  version: 0,
  rows: [],
  nextKey: 1,
  errors: {},
  warnings: {},
  listErrors: [],
  changedElsewhere: [],
};

export const statusOf = (draftRow: DraftRow): DraftRowStatus => {
  if (draftRow.deleted) {
    return "deleted";
  }
  if (!draftRow.saved) {
    return "new";
  }
  return isSameRow(draftRow.saved, draftRow.row) ? "unchanged" : "edited";
};

export const hasChanges = (draft: ModelPricesDraft) =>
  draft.rows.some((draftRow) => statusOf(draftRow) !== "unchanged");

export const changeCounts = (draft: ModelPricesDraft) => {
  const counts = { created: 0, updated: 0, deleted: 0 };
  draft.rows.forEach((draftRow) => {
    const status = statusOf(draftRow);
    if (status === "new") counts.created += 1;
    if (status === "edited") counts.updated += 1;
    if (status === "deleted") counts.deleted += 1;
  });
  return counts;
};

// New rows are shown on top, newest first, but are checked and sent after the saved rows, oldest first, so a
// duplicate is reported on the row just added rather than on the saved row it repeats.
export const inSaveOrder = (rows: DraftRow[]) => [
  ...rows.filter((draftRow) => draftRow.saved),
  ...rows.filter((draftRow) => !draftRow.saved).reverse(),
];

export const toReplaceRequest = (
  draft: ModelPricesDraft,
): { request: ModelPriceReplace; keys: string[] } => {
  const kept = inSaveOrder(draft.rows).filter((draftRow) => !draftRow.deleted);
  return {
    request: {
      rows: kept.map((draftRow) => toEditableRow(draftRow.row)),
      expected_version: draft.version,
    },
    keys: kept.map((draftRow) => draftRow.key),
  };
};

const savedRow = (row: ModelPriceRow, index: number): DraftRow => ({
  key: row.id ?? `saved-${index}`,
  saved: row,
  changes: {},
  row,
  deleted: false,
});

const withoutProblems = (draft: ModelPricesDraft, key: string) => {
  const errors = { ...draft.errors };
  const warnings = { ...draft.warnings };
  delete errors[key];
  delete warnings[key];
  return { errors, warnings };
};

const load = (
  draft: ModelPricesDraft,
  list: ModelPriceList,
): ModelPricesDraft => {
  const fresh = list.rows.map(savedRow);
  if (!hasChanges(draft)) {
    return {
      ...EMPTY_DRAFT,
      nextKey: draft.nextKey,
      version: list.version,
      rows: fresh,
    };
  }

  const mineByKey = new Map(
    draft.rows.map((draftRow) => [draftRow.key, draftRow]),
  );
  const freshKeys = new Set(fresh.map((draftRow) => draftRow.key));
  const changedElsewhere: string[] = [];

  const rebased = fresh.map((freshRow) => {
    const mine = mineByKey.get(freshRow.key);
    if (!mine?.saved || statusOf(mine) === "unchanged") {
      return freshRow;
    }
    const theyChanged = !isSameRow(mine.saved, freshRow.row);
    if (mine.deleted) {
      if (theyChanged) changedElsewhere.push(freshRow.key);
      return { ...freshRow, deleted: true };
    }
    const row = { ...freshRow.row, ...mine.changes };
    if (isSameRow(row, freshRow.row)) {
      return freshRow;
    }
    if (theyChanged) changedElsewhere.push(freshRow.key);
    return { ...freshRow, changes: mine.changes, row };
  });

  const added: DraftRow[] = [];
  draft.rows.forEach((mine) => {
    if (!mine.saved) {
      added.push(mine);
    } else if (!freshKeys.has(mine.key) && statusOf(mine) === "edited") {
      // Someone else deleted a row this user edited: keep the edit as a new row rather than lose it.
      changedElsewhere.push(mine.key);
      added.push({
        key: mine.key,
        changes: { ...mine.row, id: undefined },
        row: { ...mine.row, id: undefined },
        deleted: false,
      });
    }
  });

  return {
    ...draft,
    version: list.version,
    rows: [...added, ...rebased],
    errors: {},
    warnings: {},
    listErrors: [],
    changedElsewhere,
  };
};

const byKeys = (keys: string[], problems: ModelPriceProblem[]) => {
  const grouped: Record<string, ModelPriceProblem[]> = {};
  const unplaced: ModelPriceProblem[] = [];
  problems.forEach((problem) => {
    const key = problem.row == null ? undefined : keys[problem.row];
    if (key === undefined) {
      unplaced.push(problem);
    } else {
      grouped[key] = [...(grouped[key] ?? []), problem];
    }
  });
  return { grouped, unplaced };
};

export const modelPricesReducer = (
  draft: ModelPricesDraft,
  action: ModelPricesDraftAction,
): ModelPricesDraft => {
  switch (action.type) {
    case "loaded":
      return load(draft, action.list);

    case "reset":
      return load({ ...EMPTY_DRAFT, nextKey: draft.nextKey }, action.list);

    case "edited":
      return {
        ...draft,
        ...withoutProblems(draft, action.key),
        rows: draft.rows.map((draftRow) => {
          if (draftRow.key !== action.key || draftRow.deleted) {
            return draftRow;
          }
          const changes = { ...draftRow.changes, ...action.changes };
          return {
            ...draftRow,
            changes,
            row: { ...draftRow.row, ...action.changes },
          };
        }),
      };

    case "added": {
      const key = `new-${draft.nextKey}`;
      const added: DraftRow = {
        key,
        changes: action.row,
        row: action.row,
        deleted: false,
      };
      return {
        ...draft,
        nextKey: draft.nextKey + 1,
        rows: [added, ...draft.rows],
      };
    }

    case "removed":
      return {
        ...draft,
        ...withoutProblems(draft, action.key),
        rows: draft.rows.flatMap((draftRow) => {
          if (draftRow.key !== action.key) return [draftRow];
          return draftRow.saved ? [{ ...draftRow, deleted: true }] : [];
        }),
      };

    case "restored":
      return {
        ...draft,
        ...withoutProblems(draft, action.key),
        changedElsewhere: draft.changedElsewhere.filter(
          (key) => key !== action.key,
        ),
        rows: draft.rows.map((draftRow) =>
          draftRow.key === action.key && draftRow.saved
            ? { ...draftRow, changes: {}, row: draftRow.saved, deleted: false }
            : draftRow,
        ),
      };

    case "discarded":
      return {
        ...EMPTY_DRAFT,
        nextKey: draft.nextKey,
        version: draft.version,
        rows: draft.rows.flatMap((draftRow) =>
          draftRow.saved
            ? [
                {
                  ...draftRow,
                  changes: {},
                  row: draftRow.saved,
                  deleted: false,
                },
              ]
            : [],
        ),
      };

    case "problemsReceived": {
      const errors = byKeys(action.keys, action.errors);
      const warnings = byKeys(action.keys, action.warnings);
      return {
        ...draft,
        errors: errors.grouped,
        warnings: warnings.grouped,
        listErrors: errors.unplaced,
      };
    }
  }
};
