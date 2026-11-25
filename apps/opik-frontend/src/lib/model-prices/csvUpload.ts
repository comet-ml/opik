import type { ModelPriceAliasOf, ModelPriceRow } from "./types";
import { PRICE_FIELDS } from "./types";
import { canonicalPrice, isDecimal } from "./rows";
import { ANY_PROVIDER_LABEL } from "./catalog";
import { LiteLlmUpload, parseLiteLlmUpload } from "./litellm";

const SPANS_OWN_PROVIDER = " on the span's own provider";

// Reads back what describeAliasTarget writes into an export's priced_like column.
const aliasTargetOf = (text: string): ModelPriceAliasOf => {
  if (text.endsWith(SPANS_OWN_PROVIDER)) {
    return { model: text.slice(0, -SPANS_OWN_PROVIDER.length).trim() };
  }
  const at = text.lastIndexOf(" on ");
  return at > 0
    ? { model: text.slice(0, at).trim(), provider: text.slice(at + 4).trim() }
    : { model: text.trim() };
};

const cell = (record: Record<string, string>, key: string) =>
  (record[key] ?? "").trim();

// RFC 4180: a quoted field may hold commas, line breaks and "" for a quote.
const readCsv = (text: string): { records: string[][]; isClosed: boolean } => {
  const input = text.replace(/^\uFEFF/, "");
  const records: string[][] = [];
  let record: string[] = [];
  let field = "";
  let inQuotes = false;
  for (let i = 0; i < input.length; i += 1) {
    const char = input[i];
    if (inQuotes) {
      if (char !== '"') {
        field += char;
      } else if (input[i + 1] === '"') {
        field += '"';
        i += 1;
      } else {
        inQuotes = false;
      }
    } else if (char === '"') {
      inQuotes = true;
    } else if (char === ",") {
      record.push(field);
      field = "";
    } else if (char === "\n" || char === "\r") {
      if (char === "\r" && input[i + 1] === "\n") i += 1;
      record.push(field);
      records.push(record);
      record = [];
      field = "";
    } else {
      field += char;
    }
  }
  if (field || record.length) {
    record.push(field);
    records.push(record);
  }
  return {
    records: records.filter((values) => values.some((value) => value.trim())),
    isClosed: !inQuotes,
  };
};

export const parsePriceCsv = (text: string): LiteLlmUpload => {
  const { records: csv, isClosed } = readCsv(text);
  if (!isClosed) {
    return {
      rows: [],
      errors: ["This isn't valid CSV. A quote is opened but never closed."],
    };
  }
  const [header = [], ...lines] = csv;
  const columns = header.map((name) => name.trim());
  if (!columns.includes("model")) {
    return {
      rows: [],
      errors: [
        'Expected a header row with a "model" column, like the one Export as CSV writes.',
      ],
    };
  }
  const records = lines.map((values) =>
    Object.fromEntries(columns.map((name, i) => [name, values[i] ?? ""])),
  );

  const rows: ModelPriceRow[] = [];
  const errors: string[] = [];
  records.forEach((record, index) => {
    const model = cell(record, "model");
    if (!model) {
      errors.push(`Line ${index + 2}: model is empty.`);
      return;
    }
    const providerText = cell(record, "provider");
    const provider = providerText === ANY_PROVIDER_LABEL ? "" : providerText;
    const note = cell(record, "note");
    const pricedLike = cell(record, "priced_like");
    if (pricedLike) {
      rows.push({
        model,
        provider,
        kind: "alias",
        alias_of: aliasTargetOf(pricedLike),
        ...(note && { note }),
      });
      return;
    }

    const row: ModelPriceRow = { model, provider, kind: "fixed" };
    let priced = false;
    PRICE_FIELDS.forEach((field) => {
      const raw = cell(record, `${field}_per_1m`);
      if (!raw) return;
      const value = canonicalPrice(raw);
      if (!isDecimal(value) || value.startsWith("-")) {
        errors.push(
          `${model}: ${field}_per_1m must be a number of dollars per 1M tokens, 0 or more.`,
        );
        return;
      }
      row[field] = value;
      priced = true;
    });
    if (note) row.note = note;
    if (priced) {
      rows.push(row);
    } else {
      errors.push(`${model}: no price columns found.`);
    }
  });
  return { rows, errors };
};

export const parsePriceUpload = (text: string): LiteLlmUpload =>
  text.trimStart().startsWith("{")
    ? parseLiteLlmUpload(text)
    : parsePriceCsv(text);
