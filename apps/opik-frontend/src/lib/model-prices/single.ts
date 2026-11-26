import type {
  ModelPriceKind,
  ModelPriceList,
  ModelPriceProblem,
  ModelPriceReplace,
  ModelPriceRow,
  PriceField,
} from "./types";
import { PRICE_FIELDS } from "./types";
import type { BuiltInPrice } from "./litellm";
import { perMillionToPerToken } from "./litellm";
import { canonicalPrice, identityOf, isSameRow, toEditableRow } from "./rows";
import { effectivePrices } from "./catalog";
import { PRICE_LABELS, rowLabel } from "./describe";
import { MAX_PRICE_PER_MILLION, MAX_ROWS } from "./validation";

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

export interface DrawerValues {
  kind: ModelPriceKind;
  aliasModel: string;
  aliasProvider: string;
  note: string;
  prices: Partial<Record<PriceField, string>>;
}

export const drawerValuesOf = (
  row: ModelPriceRow | undefined,
  builtIn: BuiltInPrice | undefined,
): DrawerValues => ({
  kind: row?.kind ?? "fixed",
  aliasModel: row?.alias_of?.model ?? "",
  aliasProvider: row?.alias_of?.provider ?? "",
  note: row?.note ?? "",
  prices: effectivePrices(builtIn, row),
});

export const isSameSavedRow = (
  left: ModelPriceRow | undefined,
  right: ModelPriceRow | undefined,
) => (left && right ? isSameRow(left, right) : left === right);

const sameText = (left: string, right: string) => left.trim() === right.trim();

// Three-way merge: a field the user edited since `base` keeps the user's value, every other field takes
// `theirs`. Sending the drawer's values as they were would silently revert someone else's save.
export const rebaseValues = (
  base: DrawerValues,
  mine: DrawerValues,
  theirs: DrawerValues,
): DrawerValues => {
  const prices: Partial<Record<PriceField, string>> = {};
  PRICE_FIELDS.forEach((field) => {
    const edited =
      canonicalPrice(mine.prices[field]) !== canonicalPrice(base.prices[field]);
    const value = edited ? mine.prices[field] : theirs.prices[field];
    if (value !== undefined) {
      prices[field] = value;
    }
  });
  return {
    kind: mine.kind !== base.kind ? mine.kind : theirs.kind,
    aliasModel: sameText(mine.aliasModel, base.aliasModel)
      ? theirs.aliasModel
      : mine.aliasModel,
    aliasProvider: sameText(mine.aliasProvider, base.aliasProvider)
      ? theirs.aliasProvider
      : mine.aliasProvider,
    note: sameText(mine.note, base.note) ? theirs.note : mine.note,
    prices,
  };
};

export interface BatchSave {
  request: ModelPriceReplace;
  positions: number[];
  duplicates: string[];
}

export const upsertMany = (
  list: ModelPriceList,
  incoming: ModelPriceRow[],
): BatchSave => {
  const byIdentity = new Map<string, ModelPriceRow>();
  list.rows.forEach((row) => byIdentity.set(identityOf(row), row));
  const uploaded = new Set<string>();
  const duplicated = new Map<string, ModelPriceRow>();
  incoming.forEach((row) => {
    const identity = identityOf(row);
    if (uploaded.has(identity)) {
      duplicated.set(identity, row);
      return;
    }
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
    duplicates: Array.from(duplicated.values()).map(
      (row) =>
        `${rowLabel(row)} appears more than once. Keep one row per model and provider.`,
    ),
  };
};

export const describeRowLimit = (
  list: ModelPriceList,
  request: ModelPriceReplace,
): string | undefined => {
  const total = request.rows.length;
  if (total <= MAX_ROWS) return undefined;
  const format = (count: number) => count.toLocaleString("en-US");
  return `The price list can hold at most ${format(
    MAX_ROWS,
  )} models. With this update it would have ${format(total)}: ${format(
    list.rows.length,
  )} saved now and ${format(total - list.rows.length)} new.`;
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
  if (value?.trim()) return "";
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

const NOT_BLOCKING_FOR_CUSTOM_PRICES = new Set([
  "provider_unpriced",
  "no_base",
]);

export const blockingWarnings = (
  warnings: ModelPriceProblem[],
  isCustomPrice: boolean,
): ModelPriceProblem[] =>
  isCustomPrice
    ? warnings.filter(
        (warning) => !NOT_BLOCKING_FOR_CUSTOM_PRICES.has(warning.code),
      )
    : warnings;

const OUT_OF_RANGE = /^(\S+) per 1M tokens is far from the built-in ([^:\s]+)/;
const PER_TOKEN_HINT = "Prices in the file are per token.";

const priceFieldOf = (field?: string | null): PriceField | undefined =>
  PRICE_FIELDS.find((name) => name === field);

const describeFieldProblem = (
  problem: ModelPriceProblem,
  field: PriceField,
): string => {
  const label = `${PRICE_LABELS[field]} price`;
  if (problem.code === "price_too_high") {
    return `${label} is over $${MAX_PRICE_PER_MILLION.toLocaleString(
      "en-US",
    )} per 1M tokens. ${PER_TOKEN_HINT}`;
  }
  if (problem.code === "price_out_of_range") {
    const match = OUT_OF_RANGE.exec(problem.message);
    const detail = match
      ? `$${match[1]} per 1M tokens is far from the built-in $${match[2]}`
      : "is far from the built-in price";
    return `${label} ${detail}. ${PER_TOKEN_HINT}`;
  }
  return problem.message.startsWith(field)
    ? `${label}${problem.message.slice(field.length)}`
    : `${label}: ${problem.message}`;
};

export const describeBatchProblem = (
  problem: ModelPriceProblem,
  rows: ModelPriceRow[],
): string => {
  const row = typeof problem.row === "number" ? rows[problem.row] : undefined;
  const field = priceFieldOf(problem.field);
  const message = field
    ? describeFieldProblem(problem, field)
    : problem.message;
  return row ? `${rowLabel(row)}: ${message}` : message;
};
