import {
  ANY_PROVIDER_LABEL,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  PRICE_FIELDS,
  describeAliasTarget,
} from "@/lib/model-prices";

export const toExportRows = (entries: CatalogEntry[]) =>
  entries.map((entry) => {
    const isAlias = entry.row?.kind === "alias";
    const prices = isAlias ? entry.aliasPrices : entry.prices;
    const row: Record<string, string> = {
      model: entry.model,
      provider: entry.provider || ANY_PROVIDER_LABEL,
      type: CATALOG_TYPE_LABELS[entry.type],
      priced_like: isAlias && entry.row ? describeAliasTarget(entry.row) : "",
    };
    PRICE_FIELDS.forEach((field) => {
      row[`${field}_per_1m`] = prices?.[field] ?? "";
    });
    row.last_updated_at = entry.lastUpdatedAt ?? "";
    return row;
  });
