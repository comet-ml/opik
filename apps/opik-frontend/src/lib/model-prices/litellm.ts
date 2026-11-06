import type { ModelPriceRow, PriceField } from "./types";
import { PRICE_FIELDS } from "./types";
import { canonicalPrice } from "./rows";

export const LITELLM_KEYS: Record<PriceField, string> = {
  input_price: "input_cost_per_token",
  output_price: "output_cost_per_token",
  cache_read_price: "cache_read_input_token_cost",
  cache_write_price: "cache_creation_input_token_cost",
};

const plain = (value: number): string => {
  const fixed = value.toFixed(12);
  return canonicalPrice(fixed);
};

// Float math turns 3e-6 * 1e6 into 2.9999999999999996, so round to 12 significant digits first.
export const perTokenToPerMillion = (perToken: number): string =>
  plain(Number((perToken * 1e6).toPrecision(12)));

const shiftLeft = (digits: string, places: number) => {
  const [integer, fraction = ""] = digits.split(".");
  const padded = integer.padStart(places + 1, "0");
  const head = padded.slice(0, padded.length - places);
  const tail = padded.slice(padded.length - places) + fraction;
  return canonicalPrice(`${head}.${tail}`);
};

export const perMillionToPerToken = (perMillion?: string | null): string => {
  const canonical = canonicalPrice(perMillion);
  if (!canonical) {
    return "";
  }
  return shiftLeft(canonical, 6);
};

export interface BuiltInPrice {
  model: string;
  provider: string;
  mode?: string;
  input_price?: string;
  output_price?: string;
  cache_read_price?: string;
  cache_write_price?: string;
}

type JsonObject = Record<string, unknown>;

const isObject = (value: unknown): value is JsonObject =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const priceOf = (entry: JsonObject, field: PriceField) => {
  const value = entry[LITELLM_KEYS[field]];
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? perTokenToPerMillion(value)
    : undefined;
};

export const parseBuiltInPrices = (file: unknown): BuiltInPrice[] => {
  if (!isObject(file)) {
    return [];
  }
  const prices: BuiltInPrice[] = [];
  Object.entries(file).forEach(([model, entry]) => {
    if (!isObject(entry) || model === "sample_spec") {
      return;
    }
    const input = priceOf(entry, "input_price");
    const output = priceOf(entry, "output_price");
    if (input === undefined && output === undefined) {
      return;
    }
    const price: BuiltInPrice = {
      model,
      provider:
        typeof entry.litellm_provider === "string"
          ? entry.litellm_provider
          : "",
    };
    if (typeof entry.mode === "string") {
      price.mode = entry.mode;
    }
    PRICE_FIELDS.forEach((field) => {
      const value = priceOf(entry, field);
      if (value !== undefined) {
        price[field] = value;
      }
    });
    prices.push(price);
  });
  return prices;
};

export interface LiteLlmUpload {
  rows: ModelPriceRow[];
  errors: string[];
}

export const parseLiteLlmUpload = (text: string): LiteLlmUpload => {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    return {
      rows: [],
      errors: ["This isn't valid JSON. Check for a missing comma or quote."],
    };
  }
  if (!isObject(parsed)) {
    return {
      rows: [],
      errors: [
        'Expected an object of models, like { "gpt-5": { "input_cost_per_token": 0.000001 } }.',
      ],
    };
  }
  const rows: ModelPriceRow[] = [];
  const errors: string[] = [];
  Object.entries(parsed).forEach(([model, entry]) => {
    if (!isObject(entry)) {
      errors.push(`${model}: expected an object of prices.`);
      return;
    }
    const row: ModelPriceRow = {
      model: model.trim(),
      provider:
        typeof entry.litellm_provider === "string"
          ? entry.litellm_provider.trim()
          : "",
      kind: "fixed",
    };
    let priced = false;
    PRICE_FIELDS.forEach((field) => {
      const value = entry[LITELLM_KEYS[field]];
      if (value === undefined || value === null) {
        return;
      }
      if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
        errors.push(
          `${model}: ${LITELLM_KEYS[field]} must be a number of dollars per token, 0 or more.`,
        );
        return;
      }
      row[field] = perTokenToPerMillion(value);
      priced = true;
    });
    if (typeof entry.note === "string" && entry.note.trim()) {
      row.note = entry.note.trim();
    }
    if (priced) {
      rows.push(row);
    } else {
      errors.push(`${model}: no price fields found.`);
    }
  });
  return { rows, errors };
};
