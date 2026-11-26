import React from "react";
import { Lightbulb } from "lucide-react";
import {
  BuiltInPrice,
  PriceField,
  canonicalPrice,
  isDecimal,
} from "@/lib/model-prices";
import { formatPrice } from "./format";
import ExpandableHint from "./ExpandableHint";

const TERMS: { field: PriceField; sub: string; tokens: string }[] = [
  { field: "input_price", sub: "in", tokens: "input tokens, not cached" },
  { field: "output_price", sub: "out", tokens: "output tokens" },
  { field: "cache_read_price", sub: "cr", tokens: "cache read tokens" },
  { field: "cache_write_price", sub: "cw", tokens: "cache write tokens" },
];

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

const Variable: React.FC<{ letter: string; sub: string }> = ({
  letter,
  sub,
}) => (
  <msub>
    <mi>{letter}</mi>
    <mtext>{sub}</mtext>
  </msub>
);

type PriceFormulaProps = {
  prices: Partial<Record<PriceField, string>>;
  builtIn?: BuiltInPrice;
};

const SOURCES: Record<string, string> = {
  "built-in": "built-in price",
  "input price": "billed at the input price",
  "no price": "no price set",
};

const PriceFormula: React.FC<PriceFormulaProps> = ({ prices, builtIn }) => {
  const terms = TERMS.map((term) => ({
    ...term,
    ...termOf(term.field, prices, builtIn),
  }));

  return (
    <ExpandableHint
      label="How the cost is calculated"
      icon={<Lightbulb className="size-3.5 shrink-0" />}
      contentTestId="price-formula"
    >
      <math display="block" className="text-sm text-foreground">
        <mrow>
          <mtext>cost</mtext>
          <mo>=</mo>
          <mfrac>
            <mrow>
              {terms.map(({ sub, price }, index) => (
                <React.Fragment key={sub}>
                  {index > 0 && <mo>+</mo>}
                  <Variable letter="T" sub={sub} />
                  <mo>·</mo>
                  <mn>{price ? formatPrice(price) : "$0"}</mn>
                </React.Fragment>
              ))}
            </mrow>
            <mn>1,000,000</mn>
          </mfrac>
        </mrow>
      </math>
      <div className="grid grid-cols-[auto_1fr] items-baseline gap-x-3 gap-y-1">
        {terms.map(({ field, sub, tokens, source }) => (
          <React.Fragment key={field}>
            <math className="text-foreground">
              <Variable letter="T" sub={sub} />
            </math>
            <span>
              {tokens}
              {source && ` · ${SOURCES[source]}`}
            </span>
          </React.Fragment>
        ))}
      </div>
    </ExpandableHint>
  );
};

export default PriceFormula;
