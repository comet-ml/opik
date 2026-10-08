import React from "react";
import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  ColumnDef,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import {
  getPrettifyConfig,
  getThreadPrettifyConfig,
  PrettifyMessageConfig,
  PrettifySource,
} from "@/lib/traces";
import { JsonNode, ROW_HEIGHT } from "@/types/shared";
import PrettyCell from "./PrettyCell";

function TestTable<TData>({
  row,
  value,
  config,
}: {
  row: TData;
  value: (row: TData) => JsonNode;
  config?: (row: TData, field: "input" | "output") => PrettifyMessageConfig;
}) {
  const columns: ColumnDef<TData, JsonNode>[] = [
    {
      id: "output",
      accessorFn: value,
      cell: PrettyCell,
      meta: { custom: { fieldType: "output", getPrettifyConfig: config } },
    },
  ];
  const table = useReactTable({
    data: [row],
    columns,
    getCoreRowModel: getCoreRowModel(),
    meta: { rowHeight: ROW_HEIGHT.large, rowHeightStyle: {} },
  });
  const cell = table.getRowModel().rows[0].getVisibleCells()[0];
  return <>{flexRender(cell.column.columnDef.cell, cell.getContext())}</>;
}

describe("PrettyCell", () => {
  it.each([0, false])("preserves scalar thread output %s", (last_message) => {
    const html = renderToStaticMarkup(
      <TestTable
        row={{ last_message }}
        value={(thread) => thread.last_message ?? null}
        config={getThreadPrettifyConfig}
      />,
    );
    expect(html).toContain(`>${last_message}</`);
  });
  it("recovers an empty trace output using the column's trace context", () => {
    const row: PrettifySource = {
      input: { "llm.output_messages.0.message.content": "Recovered answer" },
    };
    const html = renderToStaticMarkup(
      <TestTable
        row={row}
        value={(trace) => trace.output ?? ""}
        config={getPrettifyConfig}
      />,
    );
    expect(html).toContain("Recovered answer");
  });
  it("uses a metadata-only hint for role-less output", () => {
    const row: PrettifySource = {
      metadata: { "openinference.span.kind": "LLM" },
      output: { messages: [{ content: "Role-less answer" }] },
    };
    expect(
      renderToStaticMarkup(
        <TestTable
          row={row}
          value={(trace) => trace.output ?? ""}
          config={getPrettifyConfig}
        />,
      ),
    ).toContain("Role-less answer");
  });
  it("renders an ordinary empty cell without inventing output", () => {
    expect(
      renderToStaticMarkup(
        <TestTable row={{}} value={() => ""} config={getPrettifyConfig} />,
      ),
    ).toContain(">-</");
  });
  it("supports a thread-shaped row without trace-specific metadata", () => {
    const row = { last_message: { answer: "Thread answer" } };
    expect(
      renderToStaticMarkup(
        <TestTable row={row} value={(thread) => thread.last_message} />,
      ),
    ).toContain("Thread answer");
  });

  it("renders canonical thread output as text through the thread column config", () => {
    const row = {
      last_message: {
        messages: [{ role: "assistant", content: "Thread answer" }],
      },
    };
    const html = renderToStaticMarkup(
      <TestTable
        row={row}
        value={(thread) => thread.last_message ?? ""}
        config={getThreadPrettifyConfig}
      />,
    );
    expect(html).toContain("Thread answer");
    expect(html).not.toContain("&quot;messages&quot;");
    expect(html).not.toContain("&quot;role&quot;");
  });
});
