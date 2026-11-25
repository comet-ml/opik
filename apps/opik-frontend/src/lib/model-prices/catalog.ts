import type { ModelPriceRow, PriceField } from "./types";
import { PRICE_FIELDS } from "./types";
import type { BuiltInPrice } from "./litellm";
import { canonicalPrice, identityOf } from "./rows";
import type { BuiltInOf } from "./describe";

export type CatalogType = "default" | "override" | "custom" | "alias";

export const CATALOG_TYPES: CatalogType[] = [
  "default",
  "override",
  "custom",
  "alias",
];

export const CATALOG_TYPE_LABELS: Record<CatalogType, string> = {
  default: "Default",
  override: "Override",
  custom: "Custom",
  alias: "Alias",
};

export const NOT_DEFAULT_TYPE = "not_default";
export const NOT_DEFAULT_LABEL = "Not default";

export interface CatalogEntry {
  key: string;
  model: string;
  provider: string;
  type: CatalogType;
  mode?: string;
  builtIn?: BuiltInPrice;
  row?: ModelPriceRow;
  prices: Partial<Record<PriceField, string>>;
  lastUpdatedAt?: string;
}

const EXTRA_FIELDS: PriceField[] = ["cache_read_price", "cache_write_price"];

const identityOfPrice = (price: BuiltInPrice) =>
  identityOf({ model: price.model, provider: price.provider, kind: "fixed" });

export const effectivePrices = (
  builtIn: BuiltInPrice | undefined,
  row: ModelPriceRow | undefined,
): Partial<Record<PriceField, string>> => {
  const prices: Partial<Record<PriceField, string>> = {};
  if (row?.kind === "alias") {
    return prices;
  }
  PRICE_FIELDS.forEach((field) => {
    const value = canonicalPrice(row?.[field]) || builtIn?.[field];
    if (value) {
      prices[field] = value;
    }
  });
  return prices;
};

export const buildCatalog = (
  builtIns: BuiltInPrice[],
  rows: ModelPriceRow[],
): CatalogEntry[] => {
  const byIdentity = new Map<string, BuiltInPrice>();
  builtIns.forEach((price) => {
    const identity = identityOfPrice(price);
    if (!byIdentity.has(identity)) {
      byIdentity.set(identity, price);
    }
  });
  const overridden = new Set<string>();
  const entries: CatalogEntry[] = rows.map((row) => {
    const identity = identityOf(row);
    const builtIn = row.kind === "alias" ? undefined : byIdentity.get(identity);
    if (builtIn) {
      overridden.add(identity);
    }
    return {
      // Keyed by model and provider, not by row, so a default that gets
      // overridden keeps its table row (and focus) instead of remounting.
      key: row.kind === "alias" ? `alias:${identity}` : identity,
      model: row.model,
      provider: row.provider ?? "",
      type: row.kind === "alias" ? "alias" : builtIn ? "override" : "custom",
      mode: builtIn?.mode,
      builtIn,
      row,
      prices: effectivePrices(builtIn, row),
      lastUpdatedAt: row.last_updated_at ?? row.created_at ?? undefined,
    };
  });
  byIdentity.forEach((builtIn, identity) => {
    if (!overridden.has(identity)) {
      entries.push({
        key: identity,
        model: builtIn.model,
        provider: builtIn.provider,
        type: "default",
        mode: builtIn.mode,
        builtIn,
        prices: effectivePrices(builtIn, undefined),
      });
    }
  });
  return entries;
};

export const findBuiltIn = (
  builtIns: BuiltInPrice[],
  model: string,
  provider: string,
): BuiltInPrice | undefined => {
  const identity = identityOf({ model, provider, kind: "fixed" });
  return builtIns.find((price) => identityOfPrice(price) === identity);
};

export const builtInLookup = (builtIns: BuiltInPrice[]): BuiltInOf => {
  const byIdentity = new Map<string, BuiltInPrice>();
  builtIns.forEach((price) => {
    const identity = identityOfPrice(price);
    if (!byIdentity.has(identity)) {
      byIdentity.set(identity, price);
    }
  });
  return (row) => byIdentity.get(identityOf(row));
};

export const extraFields = (entry: CatalogEntry): PriceField[] =>
  EXTRA_FIELDS.filter((field) => entry.prices[field]);

export const ANY_PROVIDER_LABEL = "Any provider";

export interface CatalogFieldFilter {
  field: string;
  operator: string;
  value: string | number;
}

export interface CatalogFilter {
  search?: string;
  filters?: CatalogFieldFilter[];
}

const TYPE_BY_LABEL: Record<string, string> = {
  default: "default",
  override: "override",
  custom: "custom",
  alias: "alias",
  [NOT_DEFAULT_LABEL.toLowerCase()]: NOT_DEFAULT_TYPE,
};

const PRICE_FILTER_FIELDS: Record<string, PriceField> = {
  input_price: "input_price",
  output_price: "output_price",
};

const textOf = (entry: CatalogEntry, field: string): string | undefined => {
  if (field === "provider") return entry.provider;
  if (field === "type") return entry.type;
  return undefined;
};

const normalizedValue = (field: string, value: string | number): string => {
  const text = String(value).trim();
  if (field === "provider") {
    return text === ANY_PROVIDER_LABEL ? "" : text;
  }
  if (field === "type") {
    return TYPE_BY_LABEL[text.toLowerCase()] ?? text.toLowerCase();
  }
  return text;
};

const compareNumbers = (
  actual: number,
  operator: string,
  expected: number,
): boolean => {
  switch (operator) {
    case "=":
      return actual === expected;
    case ">":
      return actual > expected;
    case ">=":
      return actual >= expected;
    case "<":
      return actual < expected;
    case "<=":
      return actual <= expected;
    default:
      return true;
  }
};

const matchesNumber = (
  entry: CatalogEntry,
  filter: CatalogFieldFilter,
): boolean => {
  const price = entry.prices[PRICE_FILTER_FIELDS[filter.field]];
  if (!price) return false;
  return compareNumbers(Number(price), filter.operator, Number(filter.value));
};

const matchesFieldFilters = (
  entry: CatalogEntry,
  filters: CatalogFieldFilter[],
): boolean => {
  const byField = new Map<string, CatalogFieldFilter[]>();
  filters.forEach((filter) => {
    byField.set(filter.field, [...(byField.get(filter.field) ?? []), filter]);
  });
  return Array.from(byField.entries()).every(([field, group]) => {
    if (field === "cache_prices") {
      return group.every((filter) =>
        filter.operator === "is_empty"
          ? !extraFields(entry).length
          : extraFields(entry).length > 0,
      );
    }
    if (PRICE_FILTER_FIELDS[field]) {
      return group.every((filter) => matchesNumber(entry, filter));
    }
    const actual = textOf(entry, field);
    if (actual === undefined) return true;
    const matches = (filter: CatalogFieldFilter) => {
      const expected = normalizedValue(field, filter.value);
      return field === "type" && expected === NOT_DEFAULT_TYPE
        ? actual !== "default"
        : expected === actual;
    };
    const included = group.filter((filter) => filter.operator === "=");
    const excluded = group.filter((filter) => filter.operator === "!=");
    return (
      (!included.length || included.some(matches)) &&
      excluded.every((filter) => !matches(filter))
    );
  });
};

const searchRank = (entry: CatalogEntry, needle: string): number => {
  const model = entry.model.toLowerCase();
  const bareModel = model.includes("/")
    ? model.slice(model.indexOf("/") + 1)
    : model;
  if (model === needle) return 0;
  if (bareModel === needle) return 1;
  if (model.startsWith(needle) || bareModel.startsWith(needle)) return 2;
  if (model.includes(needle)) return 3;
  return 4;
};

const matchesSearch = (entry: CatalogEntry, needle: string): boolean =>
  !needle ||
  entry.model.toLowerCase().includes(needle) ||
  entry.provider.toLowerCase().includes(needle) ||
  (entry.row?.alias_of?.model ?? "").toLowerCase().includes(needle);

export const filterCatalog = (
  entries: CatalogEntry[],
  filter: CatalogFilter,
): CatalogEntry[] => {
  const needle = (filter.search ?? "").trim().toLowerCase();
  const filters = filter.filters ?? [];
  return entries.filter(
    (entry) =>
      matchesSearch(entry, needle) && matchesFieldFilters(entry, filters),
  );
};

export const sortCatalog = (
  entries: CatalogEntry[],
  direction: "asc" | "desc",
  search?: string,
): CatalogEntry[] => {
  const sign = direction === "asc" ? 1 : -1;
  const needle = (search ?? "").trim().toLowerCase();
  return [...entries].sort(
    (left, right) =>
      (needle ? searchRank(left, needle) - searchRank(right, needle) : 0) ||
      sign *
        left.model.localeCompare(right.model, undefined, {
          sensitivity: "base",
        }) ||
      sign * left.provider.localeCompare(right.provider),
  );
};

export const providersOf = (entries: CatalogEntry[]): string[] => {
  const providers = Array.from(new Set(entries.map((entry) => entry.provider)));
  const named = providers
    .filter(Boolean)
    .sort((left, right) => left.localeCompare(right));
  return providers.includes("") ? [ANY_PROVIDER_LABEL, ...named] : named;
};
