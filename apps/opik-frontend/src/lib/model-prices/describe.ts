import type {
  ModelPriceHistoryEntry,
  ModelPriceReplaceResult,
  ModelPriceRow,
  PriceField,
} from "./types";
import { PRICE_FIELDS } from "./types";
import { toEditableRow } from "./rows";

export const PRICE_LABELS: Record<PriceField, string> = {
  input_price: "Input",
  output_price: "Output",
  cache_read_price: "Cache read",
  cache_write_price: "Cache write",
};

export const describeProvider = (provider?: string | null) =>
  provider?.trim() ? provider.trim() : "any provider";

export const rowLabel = (row: ModelPriceRow) =>
  `${row.model} on ${describeProvider(row.provider)}`;

export const describeAliasTarget = (row: ModelPriceRow) => {
  const model = row.alias_of?.model?.trim() ?? "";
  const provider = row.alias_of?.provider?.trim();
  return provider
    ? `${model} on ${provider}`
    : `${model} on the span's own provider`;
};

export const describePrices = (row: ModelPriceRow) => {
  const editable = toEditableRow(row);
  const parts = PRICE_FIELDS.filter((field) => editable[field]).map(
    (field) => `${PRICE_LABELS[field].toLowerCase()} ${editable[field]}`,
  );
  return parts.length ? parts.join(" · ") : "built-in prices";
};

export const describeRowPrice = (row: ModelPriceRow) =>
  row.kind === "alias"
    ? `priced like ${describeAliasTarget(row)}`
    : describePrices(row);

export const describeChanges = (
  before: ModelPriceRow,
  after: ModelPriceRow,
): string[] => {
  const from = toEditableRow(before);
  const to = toEditableRow(after);
  const lines: string[] = [];
  if (from.model !== to.model) {
    lines.push(`model ${from.model} → ${to.model}`);
  }
  if (from.provider !== to.provider) {
    lines.push(
      `provider ${describeProvider(from.provider)} → ${describeProvider(
        to.provider,
      )}`,
    );
  }
  if (from.kind !== to.kind) {
    lines.push(
      to.kind === "alias"
        ? `now priced like ${describeAliasTarget(to)}`
        : `now has its own prices: ${describePrices(to)}`,
    );
  } else if (to.kind === "alias") {
    if (describeAliasTarget(from) !== describeAliasTarget(to)) {
      lines.push(
        `priced like ${describeAliasTarget(from)} → ${describeAliasTarget(to)}`,
      );
    }
  } else {
    PRICE_FIELDS.forEach((field) => {
      if (from[field] !== to[field]) {
        lines.push(
          `${PRICE_LABELS[field].toLowerCase()} ${
            from[field] ?? "built-in"
          } → ${to[field] ?? "built-in"}`,
        );
      }
    });
  }
  if (from.note !== to.note) {
    lines.push("note changed");
  }
  return lines;
};

export const describeHistoryEntry = (entry: ModelPriceHistoryEntry) => {
  if (entry.action === "created" && entry.after) {
    return `added ${rowLabel(entry.after)}: ${describeRowPrice(entry.after)}`;
  }
  if (entry.action === "deleted" && entry.before) {
    return `removed ${rowLabel(entry.before)}`;
  }
  if (entry.before && entry.after) {
    const changes = describeChanges(entry.before, entry.after);
    return `${rowLabel(entry.after)}: ${
      changes.length ? changes.join(", ") : "no visible change"
    }`;
  }
  return `${entry.action} a row`;
};

export interface HistoryVersion {
  version: number;
  createdAt: string;
  createdBy: string;
  lines: string[];
}

export const groupHistory = (
  entries: ModelPriceHistoryEntry[],
): HistoryVersion[] => {
  const versions: HistoryVersion[] = [];
  entries.forEach((entry) => {
    let current = versions[versions.length - 1];
    if (!current || current.version !== entry.list_version) {
      current = {
        version: entry.list_version,
        createdAt: entry.created_at,
        createdBy: entry.created_by,
        lines: [],
      };
      versions.push(current);
    }
    current.lines.push(describeHistoryEntry(entry));
  });
  return versions;
};

export const describeReplaceResult = (result: ModelPriceReplaceResult) => ({
  created: result.created.map(
    (row) => `${rowLabel(row)}: ${describeRowPrice(row)}`,
  ),
  updated: result.updated.map((change) => {
    const changes = describeChanges(change.before, change.after);
    return `${rowLabel(change.after)}: ${
      changes.length ? changes.join(", ") : "no visible change"
    }`;
  }),
  deleted: result.deleted.map((row) => rowLabel(row)),
});
