import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import PassRateCell from "@/shared/DataTableCells/PassRateCell";
import { TooltipProvider } from "@/ui/tooltip";

type Row = { id: string };

const context = (row: Record<string, unknown>, aggregation = {}) =>
  ({
    row: { id: "row", original: row },
    column: {
      columnDef: { meta: { custom: { aggregationKey: "pass_rate_avg" } } },
    },
    table: { options: { meta: { aggregationMap: { row: aggregation } } } },
  }) as unknown as CellContext<Row, unknown>;

// Radix marks its tooltip trigger with data-state only when the child element
// accepts the trigger's ref and props; without that the tooltip never opens.
describe("PassRateCell tooltip trigger", () => {
  it("attaches the tooltip to an experiment row's pill", () => {
    const { getByText } = render(
      <TooltipProvider>
        <PassRateCell
          {...context({ pass_rate: 0.8, passed_count: 4, total_count: 5 })}
        />
      </TooltipProvider>,
    );

    expect(getByText("80%").closest("[data-state]")).not.toBeNull();
  });

  it("attaches the tooltip to a group row's pill", () => {
    const { getByText } = render(
      <TooltipProvider>
        <PassRateCell.Aggregation
          {...context(
            {},
            { pass_rate_avg: 0.8, passed_count_sum: 12, total_count_sum: 15 },
          )}
        />
      </TooltipProvider>,
    );

    expect(getByText("80%").closest("[data-state]")).not.toBeNull();
  });
});

// The pill is green only at exactly 100%, so a failing rate must never read
// "100%" (e.g. 999 of 1000 items, or a group average of 0.9997).
describe("PassRateCell label below 100%", () => {
  it("caps an experiment row just under 100% at 99%", () => {
    const { getByText, queryByText } = render(
      <TooltipProvider>
        <PassRateCell
          {...context({
            pass_rate: 0.999,
            passed_count: 999,
            total_count: 1000,
          })}
        />
      </TooltipProvider>,
    );

    expect(getByText("99%")).toBeTruthy();
    expect(queryByText("100%")).toBeNull();
  });

  it("caps a group row just under 100% at 99% on the red tag", () => {
    const { getByText } = render(
      <TooltipProvider>
        <PassRateCell.Aggregation
          {...context(
            {},
            {
              pass_rate_avg: 0.9997,
              passed_count_sum: 2999,
              total_count_sum: 3000,
            },
          )}
        />
      </TooltipProvider>,
    );

    expect(getByText("99%").closest("[class*='--tag-red-bg']")).not.toBeNull();
  });

  it("shows 100% only when every item passed", () => {
    const { getByText } = render(
      <TooltipProvider>
        <PassRateCell.Aggregation
          {...context(
            {},
            { pass_rate_avg: 1, passed_count_sum: 15, total_count_sum: 15 },
          )}
        />
      </TooltipProvider>,
    );

    expect(
      getByText("100%").closest("[class*='--tag-green-bg']"),
    ).not.toBeNull();
  });
});
