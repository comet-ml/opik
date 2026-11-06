import type {
  ModelPriceList,
  ModelPriceProblem,
  ModelPriceReplace,
  ModelPriceRow,
  PriceField,
} from "./types";
import { PRICE_FIELDS } from "./types";
import type { BuiltInPrice } from "./litellm";
import { canonicalPrice, identityOf, toEditableRow } from "./rows";

export interface SingleSave {
  request: ModelPriceReplace;
  index: number;
}

export const replaceOne = (
  list: ModelPriceList,
  original: ModelPriceRow | undefined,
  next: ModelPriceRow | undefined,
): SingleSave => {
  const originalIdentity = original ? identityOf(original) : undefined;
  const rows: ModelPriceRow[] = [];
  let index = -1;
  list.rows.forEach((row) => {
    if (identityOf(row) !== originalIdentity) {
      rows.push(toEditableRow(row));
    } else if (next) {
      index = rows.length;
      rows.push(toEditableRow(next));
    }
  });
  if (next && index < 0) {
    index = rows.length;
    rows.push(toEditableRow(next));
  }
  return { request: { rows, expected_version: list.version }, index };
};

export const upsertMany = (
  list: ModelPriceList,
  incoming: ModelPriceRow[],
): ModelPriceReplace => {
  const byIdentity = new Map<string, ModelPriceRow>();
  list.rows.forEach((row) => byIdentity.set(identityOf(row), row));
  incoming.forEach((row) => {
    const identity = identityOf(row);
    const saved = byIdentity.get(identity);
    byIdentity.set(identity, saved ? { ...saved, ...row } : row);
  });
  return {
    rows: Array.from(byIdentity.values()).map(toEditableRow),
    expected_version: list.version,
  };
};

export const overrideFields = (
  builtIn: BuiltInPrice | undefined,
  values: Partial<Record<PriceField, string>>,
): Partial<Record<PriceField, string>> => {
  const fields: Partial<Record<PriceField, string>> = {};
  PRICE_FIELDS.forEach((field) => {
    const value = canonicalPrice(values[field]);
    if (value && value !== canonicalPrice(builtIn?.[field])) {
      fields[field] = value;
    }
  });
  return fields;
};

export const problemsForRow = (
  problems: ModelPriceProblem[],
  index: number,
): ModelPriceProblem[] =>
  problems.filter(
    (problem) =>
      problem.row === index ||
      problem.row === null ||
      problem.row === undefined,
  );
