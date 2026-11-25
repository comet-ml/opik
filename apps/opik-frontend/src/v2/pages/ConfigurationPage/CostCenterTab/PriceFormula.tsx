import React from "react";
import {
  BuiltInPrice,
  PRICE_FIELDS,
  PriceField,
  canonicalPrice,
  isDecimal,
} from "@/lib/model-prices";
import { formatPrice } from "./format";

const TOKENS: Record<PriceField, string> = {
  input_price: "Input tokens (not cached)",
  output_price: "Output tokens",
  cache_read_price: "Cache read tokens",
  cache_write_price: "Cache write tokens",
};

type Term = { price?: string; source?: string };

// Follows the backend's cost calculation: an empty field keeps the built-in
// price, and a cache price that is set nowhere falls back to the input price.
const termOf = (
  field: PriceField,
  prices: Partial<Record<PriceField, string>>,
  builtIn?: BuiltInPrice,
): Term => {
  const own = canonicalPrice(prices[field]);
  const fromBuiltIn = canonicalPrice(builtIn?.[field]);
  if (own && isDecimal(own)) {
    return { price: own, source: own === fromBuiltIn ? "built-in" : undefined };
  }
  if (fromBuiltIn) return { price: fromBuiltIn, source: "built-in" };
  if (field === "cache_read_price" || field === "cache_write_price") {
    return {
      price: termOf("input_price", prices, builtIn).price,
      source: "input price",
    };
  }
  return { source: "no price" };
};

type PriceFormulaProps = {
  prices: Partial<Record<PriceField, string>>;
  builtIn?: BuiltInPrice;
};

const PriceFormula: React.FC<PriceFormulaProps> = ({ prices, builtIn }) => (
  <div
    className="comet-body-xs space-y-1 rounded-md border border-border p-3 text-muted-slate"
    data-testid="price-formula"
  >
    <p className="comet-body-xs-accented text-foreground">Cost of a call</p>
    {PRICE_FIELDS.map((field, index) => {
      const { price, source } = termOf(field, prices, builtIn);
      return (
        <p key={field}>
          {index > 0 && "+ "}
          {TOKENS[field]} ×{" "}
          <span className="text-foreground">
            {price ? formatPrice(price) : "$0"}
          </span>
          {source && ` (${source})`}
        </p>
      );
    })}
    <p className="pt-1">
      Prices are per 1M tokens, so the sum is divided by 1,000,000.
    </p>
  </div>
);

export default PriceFormula;
