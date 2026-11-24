import React, { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { COST_LINE_LABELS, CostLine } from "@/lib/model-prices";
import { formatExactCost } from "@/lib/money";
import { formatPrice } from "./format";

const formatTokens = (tokens: number) => tokens.toLocaleString("en-US");

const rateOf = (line: CostLine) => formatPrice(line.ratePerMillion) ?? "$0";

type CostLinesTableProps = {
  lines: CostLine[];
};

const CostLinesTable: React.FC<CostLinesTableProps> = ({ lines }) => {
  const [isDetailsOpen, setIsDetailsOpen] = useState(false);
  const DetailsIcon = isDetailsOpen ? ChevronDown : ChevronRight;

  return (
    <div className="flex flex-col gap-2">
      <div className="comet-body-xs grid grid-cols-[1fr_auto_auto_auto] gap-x-4 gap-y-1 tabular-nums">
        <span className="text-muted-slate">Field</span>
        <span className="text-right text-muted-slate">Tokens</span>
        <span className="text-right text-muted-slate">Rate / 1M tok</span>
        <span className="text-right text-muted-slate">Cost</span>
        {lines.map((line) => (
          <React.Fragment key={line.name}>
            <span>{COST_LINE_LABELS[line.name]}</span>
            <span className="text-right">{formatTokens(line.tokens)}</span>
            <span className="text-right">
              {rateOf(line)}
              {line.usesInputRate && <sup className="text-muted-slate">*</sup>}
            </span>
            <span className="text-right">{formatExactCost(line.cost)}</span>
          </React.Fragment>
        ))}
      </div>
      {lines.some((line) => line.usesInputRate) && (
        <span className="comet-body-xs text-muted-slate">
          * No cache price, so these tokens bill at the input rate.
        </span>
      )}
      <button
        type="button"
        className="comet-body-xs flex w-fit items-center gap-1 text-muted-slate hover:text-foreground"
        onClick={() => setIsDetailsOpen((open) => !open)}
        aria-expanded={isDetailsOpen}
      >
        <DetailsIcon className="size-3.5" />
        <span>Calculation details</span>
      </button>
      {isDetailsOpen && (
        <div className="comet-body-xs flex flex-col gap-1 rounded-md bg-muted px-2 py-1.5">
          <span className="text-muted-slate">
            Cost = tokens × rate ÷ 1,000,000
          </span>
          <div className="grid grid-cols-[auto_1fr_auto] gap-x-3 gap-y-1 tabular-nums">
            {lines.map((line) => (
              <React.Fragment key={line.name}>
                <span>{COST_LINE_LABELS[line.name]}</span>
                <span className="min-w-0 break-all font-mono text-muted-slate">
                  {line.source || "—"}
                </span>
                <span className="text-right">
                  {`${formatTokens(line.tokens)} × ${rateOf(
                    line,
                  )} ÷ 1M = ${formatExactCost(line.cost)}`}
                </span>
              </React.Fragment>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default CostLinesTable;
