import type { ModelPriceRow } from "./types";
import { PRICE_FIELDS } from "./types";

const DECIMAL = /^-?(\d+\.?\d*|\.\d+)$/;

const text = (value?: string | null) => (value ?? "").trim();

export const isDecimal = (value: string) => DECIMAL.test(value.trim());

export const canonicalPrice = (value?: string | null): string => {
  const trimmed = text(value);
  if (!isDecimal(trimmed)) {
    return trimmed;
  }
  const negative = trimmed.startsWith("-");
  const [rawInteger, rawFraction = ""] = trimmed.replace("-", "").split(".");
  const integer = rawInteger.replace(/^0+(?=\d)/, "") || "0";
  const fraction = rawFraction.replace(/0+$/, "");
  const plain = fraction ? `${integer}.${fraction}` : integer;
  return negative && plain !== "0" ? `-${plain}` : plain;
};

export const toEditableRow = (row: ModelPriceRow): ModelPriceRow => {
  const editable: ModelPriceRow = {
    model: text(row.model),
    provider: text(row.provider),
    kind: row.kind,
  };
  if (row.kind === "alias") {
    editable.alias_of = {
      model: text(row.alias_of?.model),
      provider: text(row.alias_of?.provider),
    };
  } else {
    PRICE_FIELDS.forEach((field) => {
      const price = canonicalPrice(row[field]);
      if (price) {
        editable[field] = price;
      }
    });
  }
  const note = text(row.note);
  if (note) {
    editable.note = note;
  }
  return editable;
};

const EDITABLE_FIELDS: (keyof ModelPriceRow)[] = [
  "model",
  "provider",
  "kind",
  ...PRICE_FIELDS,
  "alias_of",
  "note",
];

const comparable = (row: ModelPriceRow, field: keyof ModelPriceRow) => {
  if (field === "alias_of") {
    return `${text(row.alias_of?.model)}|${text(row.alias_of?.provider)}`;
  }
  const value = row[field] as string | null | undefined;
  return (PRICE_FIELDS as string[]).includes(field)
    ? canonicalPrice(value)
    : text(value);
};

export const changedFields = (
  saved: ModelPriceRow,
  row: ModelPriceRow,
): Partial<ModelPriceRow> => {
  const changes: Partial<ModelPriceRow> = {};
  EDITABLE_FIELDS.forEach((field) => {
    if (comparable(saved, field) !== comparable(row, field)) {
      Object.assign(changes, { [field]: row[field] });
    }
  });
  return changes;
};

export const isSameRow = (left: ModelPriceRow, right: ModelPriceRow) =>
  JSON.stringify(toEditableRow(left)) === JSON.stringify(toEditableRow(right));

export const identityOf = (row: ModelPriceRow) =>
  `${text(row.model).toLowerCase()}|${text(row.provider).toLowerCase()}`;
