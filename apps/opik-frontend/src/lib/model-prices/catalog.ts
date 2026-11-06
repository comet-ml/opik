import type { ModelPriceRow, PriceField } from "./types";
import { PRICE_FIELDS } from "./types";
import type { BuiltInPrice } from "./litellm";
import { canonicalPrice, identityOf } from "./rows";

export type CatalogType = "default" | "override" | "custom";

export const CATALOG_TYPES: CatalogType[] = ["default", "override", "custom"];

export const CATALOG_TYPE_LABELS: Record<CatalogType, string> = {
  default: "Default",
  override: "Override",
  custom: "Custom",
};

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

const effectivePrices = (
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
      key: `org:${row.id ?? identity}`,
      model: row.model,
      provider: row.provider ?? "",
      type: builtIn ? "override" : "custom",
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
        key: `built-in:${identity}`,
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

export const extraFields = (entry: CatalogEntry): PriceField[] =>
  EXTRA_FIELDS.filter((field) => entry.prices[field]);

export interface CatalogFilter {
  search?: string;
  providers?: string[];
  types?: CatalogType[];
}

export const filterCatalog = (
  entries: CatalogEntry[],
  filter: CatalogFilter,
): CatalogEntry[] => {
  const needle = (filter.search ?? "").trim().toLowerCase();
  const providers = new Set(filter.providers ?? []);
  const types = new Set(filter.types ?? []);
  return entries.filter(
    (entry) =>
      (!needle ||
        entry.model.toLowerCase().includes(needle) ||
        entry.provider.toLowerCase().includes(needle) ||
        (entry.row?.alias_of?.model ?? "").toLowerCase().includes(needle)) &&
      (!providers.size || providers.has(entry.provider)) &&
      (!types.size || types.has(entry.type)),
  );
};

export const sortCatalog = (
  entries: CatalogEntry[],
  direction: "asc" | "desc",
): CatalogEntry[] => {
  const sign = direction === "asc" ? 1 : -1;
  return [...entries].sort(
    (left, right) =>
      sign *
        left.model.localeCompare(right.model, undefined, {
          sensitivity: "base",
        }) || sign * left.provider.localeCompare(right.provider),
  );
};

export const providersOf = (entries: CatalogEntry[]): string[] =>
  Array.from(new Set(entries.map((entry) => entry.provider)))
    .filter(Boolean)
    .sort((left, right) => left.localeCompare(right));
