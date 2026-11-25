import React from "react";
import { Lightbulb } from "lucide-react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import {
  BuiltInPrice,
  PriceField,
  canonicalPrice,
  isDecimal,
} from "@/lib/model-prices";
import { formatPrice } from "./format";

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

const PriceFormula: React.FC<PriceFormulaProps> = ({ prices, builtIn }) => (
  <Accordion type="single" collapsible>
    <AccordionItem value="formula" className="border-b-0">
      {/* The icon sits in a span: the trigger rotates every svg directly inside it. */}
      <AccordionTrigger className="comet-body-xs h-auto justify-start gap-1 px-0 py-0 text-muted-slate hover:text-foreground [&>svg]:size-3.5">
        <span className="flex items-center gap-1">
          <Lightbulb className="size-3.5 shrink-0" />
          How the cost is calculated
        </span>
      </AccordionTrigger>
      <AccordionContent className="pb-0 pt-2">
        <div
          className="comet-body-xs space-y-3 rounded-md border border-border p-3 text-muted-slate"
          data-testid="price-formula"
        >
          <math display="block" className="text-base text-foreground">
            <mrow>
              <mtext>cost</mtext>
              <mo>=</mo>
              <mfrac>
                <mrow>
                  {TERMS.map(({ sub }, index) => (
                    <React.Fragment key={sub}>
                      {index > 0 && <mo>+</mo>}
                      <Variable letter="T" sub={sub} />
                      <mo>·</mo>
                      <Variable letter="P" sub={sub} />
                    </React.Fragment>
                  ))}
                </mrow>
                <mn>1,000,000</mn>
              </mfrac>
            </mrow>
          </math>
          <div className="space-y-1">
            <p>
              <math className="text-foreground">
                <mi>T</mi>
              </math>{" "}
              is the number of tokens in a call,{" "}
              <math className="text-foreground">
                <mi>P</mi>
              </math>{" "}
              the price per 1M tokens:
            </p>
            <div className="grid grid-cols-[auto_auto_1fr] items-baseline gap-x-3 gap-y-1">
              {TERMS.map(({ field, sub, tokens }) => {
                const { price, source } = termOf(field, prices, builtIn);
                return (
                  <React.Fragment key={field}>
                    <math className="text-foreground">
                      <Variable letter="P" sub={sub} />
                    </math>
                    <span>
                      <span className="text-foreground">
                        {price ? formatPrice(price) : "$0"}
                      </span>
                      {source && ` (${source})`}
                    </span>
                    <span>{tokens}</span>
                  </React.Fragment>
                );
              })}
            </div>
          </div>
        </div>
      </AccordionContent>
    </AccordionItem>
  </Accordion>
);

export default PriceFormula;
