import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { ColumnDef } from "@tanstack/react-table";

import DataTable from "@/shared/DataTable/DataTable";
import { generateSelectColumDef } from "@/shared/DataTable/utils";
import { TooltipProvider } from "@/ui/tooltip";

type Row = { id: string; name: string; value: number };

const columns: ColumnDef<Row>[] = [
  generateSelectColumDef<Row>(),
  { accessorKey: "name", header: "Name", size: 300 },
  { accessorKey: "value", header: "Value", size: 100 },
];

const data: Row[] = [{ id: "1", name: "first", value: 1 }];

// The table is table-layout: fixed at width 100%, which spreads spare width over
// every px-sized column. Only data columns may take that spare width, so the
// checkbox column must keep its exact px size.
describe("DataTable column widths", () => {
  it("keeps the select column at its px size and shares the rest by percentage", () => {
    const { container } = render(
      <TooltipProvider>
        <DataTable columns={columns} data={data} getRowId={(r) => r.id} />
      </TooltipProvider>,
    );

    const widths = [...container.querySelectorAll("colgroup col")].map(
      (col) => (col as HTMLElement).style.width,
    );

    expect(widths).toEqual(["40px", "75%", "25%"]);
  });
});
