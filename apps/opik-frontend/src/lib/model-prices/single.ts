import type {
  ModelPriceList,
  ModelPriceProblem,
  ModelPriceReplace,
  ModelPriceRow,
  PriceField,
} from "./types";
import { PRICE_FIELDS } from "./types";
import type { BuiltInPrice } from "./litellm";
import { perMillionToPerToken } from "./litellm";
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

export interface BatchSave {
  request: ModelPriceReplace;
  positions: number[];
}

export const upsertMany = (
  list: ModelPriceList,
  incoming: ModelPriceRow[],
): BatchSave => {
  const byIdentity = new Map<string, ModelPriceRow>();
  list.rows.forEach((row) => byIdentity.set(identityOf(row), row));
  const uploaded = new Set<string>();
  incoming.forEach((row) => {
    const identity = identityOf(row);
    const saved = byIdentity.get(identity);
    byIdentity.set(identity, saved ? { ...saved, ...row } : row);
    uploaded.add(identity);
  });
  const identities = Array.from(byIdentity.keys());
  return {
    request: {
      rows: Array.from(byIdentity.values()).map(toEditableRow),
      expected_version: list.version,
    },
    positions: identities
      .map((identity, position) => (uploaded.has(identity) ? position : -1))
      .filter((position) => position >= 0),
  };
};

export const problemsForRows = (
  problems: ModelPriceProblem[],
  positions: number[],
): ModelPriceProblem[] => {
  const wanted = new Set(positions);
  return problems.filter(
    (problem) =>
      problem.row === null ||
      problem.row === undefined ||
      wanted.has(problem.row),
  );
};

export const describePerToken = (
  value: string | undefined,
  builtInValue: string | undefined,
): string => {
  const own = perMillionToPerToken(value);
  if (own) return `= ${own} / tok`;
  const fallback = perMillionToPerToken(builtInValue);
  return fallback ? `= ${fallback} / tok (built-in)` : "= no price";
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
