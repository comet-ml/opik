import type {
  ModelPriceHistoryEntry,
  ModelPriceReplaceResult,
  ModelPriceRow,
  PriceField,
} from "./types";
import { PRICE_FIELDS } from "./types";
import type { BuiltInPrice } from "./litellm";
import { toEditableRow } from "./rows";

export const PRICE_LABELS: Record<PriceField, string> = {
  input_price: "Input",
  output_price: "Output",
  cache_read_price: "Cache read",
  cache_write_price: "Cache write",
};

export type BuiltInOf = (row: ModelPriceRow) => BuiltInPrice | undefined;

export const describeProvider = (provider?: string | null) =>
  provider?.trim() ? provider.trim() : "any provider";

export const rowLabel = (row: ModelPriceRow) =>
  `${row.model} on ${describeProvider(row.provider)}`;

export const describeRemoval = (fallsBackToBuiltIn: boolean) =>
  fallsBackToBuiltIn
    ? "Spans saved after this use Opik's built-in price for this model. Spans already saved keep their cost."
    : "Spans saved after this won't have a price for this model. Spans already saved keep their cost.";

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
  builtInOf: BuiltInOf,
): string[] => {
  const from = toEditableRow(before);
  const to = toEditableRow(after);
  const unset = (row: ModelPriceRow, field: PriceField) =>
    builtInOf(row)?.[field] ? "built-in" : "not set";
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
            from[field] ?? unset(from, field)
          } → ${to[field] ?? unset(to, field)}`,
        );
      }
    });
  }
  if (from.note !== to.note) {
    lines.push("note changed");
  }
  return lines;
};

export const describeHistoryEntry = (
  entry: ModelPriceHistoryEntry,
  builtInOf: BuiltInOf,
) => {
  if (entry.action === "created" && entry.after) {
    return `added ${rowLabel(entry.after)}: ${describeRowPrice(entry.after)}`;
  }
  if (entry.action === "deleted" && entry.before) {
    return `removed ${rowLabel(entry.before)}`;
  }
  if (entry.before && entry.after) {
    const changes = describeChanges(entry.before, entry.after, builtInOf);
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
  added: number;
  changed: number;
  removed: number;
  lines: string[];
}

export const groupHistory = (
  entries: ModelPriceHistoryEntry[],
  builtInOf: BuiltInOf,
): HistoryVersion[] => {
  const versions: HistoryVersion[] = [];
  entries.forEach((entry) => {
    let current = versions[versions.length - 1];
    if (!current || current.version !== entry.list_version) {
      current = {
        version: entry.list_version,
        createdAt: entry.created_at,
        createdBy: entry.created_by,
        added: 0,
        changed: 0,
        removed: 0,
        lines: [],
      };
      versions.push(current);
    }
    if (entry.action === "created") current.added += 1;
    if (entry.action === "updated") current.changed += 1;
    if (entry.action === "deleted") current.removed += 1;
    current.lines.push(describeHistoryEntry(entry, builtInOf));
  });
  return versions;
};

// One version's changes can run onto the next page, so while older pages are
// not loaded, the oldest version so far may be missing some and is held back.
export const groupLoadedHistory = (
  entries: ModelPriceHistoryEntry[],
  hasOlder: boolean,
  builtInOf: BuiltInOf,
): HistoryVersion[] => {
  const versions = groupHistory(entries, builtInOf);
  return hasOlder ? versions.slice(0, -1) : versions;
};

export const describeReplaceResult = (
  result: ModelPriceReplaceResult,
  builtInOf: BuiltInOf,
) => ({
  created: result.created.map(
    (row) => `${rowLabel(row)}: ${describeRowPrice(row)}`,
  ),
  updated: result.updated.map((change) => {
    const changes = describeChanges(change.before, change.after, builtInOf);
    return `${rowLabel(change.after)}: ${
      changes.length ? changes.join(", ") : "no visible change"
    }`;
  }),
  deleted: result.deleted.map((row) => rowLabel(row)),
});

export const summarizeBatch = (counts: {
  added: number;
  changed: number;
  unchanged: number;
}) => {
  const parts = [
    counts.added ? `${counts.added} added` : "",
    counts.changed ? `${counts.changed} changed` : "",
    counts.unchanged ? `${counts.unchanged} unchanged` : "",
  ].filter(Boolean);
  return parts.length ? parts.join(", ") : "Nothing changes";
};
