import {
  ANY_PROVIDER_LABEL,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  PRICE_FIELDS,
  PriceField,
  canonicalPrice,
  describeAliasTarget,
} from "@/lib/model-prices";

// An organization row writes only the rates it sets itself. Writing the
// built-in rates it inherits would make re-importing the file save them as
// the organization's own, and the row would stop following built-in updates.
const exportedPrices = (
  entry: CatalogEntry,
): Partial<Record<PriceField, string>> | undefined => {
  if (entry.row?.kind === "alias") return entry.aliasPrices;
  if (!entry.row) return entry.prices;
  const { row } = entry;
  return Object.fromEntries(
    PRICE_FIELDS.map((field) => [field, canonicalPrice(row[field])]),
  );
};

export const toExportRows = (entries: CatalogEntry[]) =>
  entries.map((entry) => {
    const isAlias = entry.row?.kind === "alias";
    const prices = exportedPrices(entry);
    const row: Record<string, string> = {
      model: entry.model,
      provider: entry.provider || ANY_PROVIDER_LABEL,
      type: CATALOG_TYPE_LABELS[entry.type],
      priced_like: isAlias && entry.row ? describeAliasTarget(entry.row) : "",
    };
    PRICE_FIELDS.forEach((field) => {
      row[`${field}_per_1m`] = prices?.[field] ?? "";
    });
    row.note = entry.row?.note?.trim() ?? "";
    row.last_updated_at = entry.lastUpdatedAt ?? "";
    return row;
  });
