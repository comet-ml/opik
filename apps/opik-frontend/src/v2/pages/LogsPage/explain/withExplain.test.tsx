import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";
import { BaseTraceData } from "@/types/traces";
import { ExplainTarget } from "@/types/assistant-sidebar";

let mockButton: ((props: { target: ExplainTarget }) => JSX.Element) | null =
  null;
// withExplain reads the slot via usePluginsStore.getState() (no per-row hook).
vi.mock("@/store/PluginsStore", () => ({
  default: { getState: () => ({ ExplainButton: mockButton }) },
}));

import { withExplain } from "./withExplain";

type Row = Partial<BaseTraceData> & { project_id?: string };

const ctx = (row: Row) =>
  ({ row: { original: row } }) as unknown as CellContext<Row, never>;

const Base = () => <span>base</span>;
const buildTarget = (row: Row): ExplainTarget | null =>
  row.project_id
    ? {
        kind: "trace.error",
        entityId: row.id as string,
        projectId: row.project_id,
        payload: {},
      }
    : null;

describe("withExplain", () => {
  it("renders the base cell and the button slot with the built target", () => {
    mockButton = ({ target }) => <span>btn:{target.entityId}</span>;
    const Cell = withExplain(Base, buildTarget);
    render(Cell(ctx({ id: "e1", project_id: "p1" })) as JSX.Element);
    expect(screen.getByText("base")).toBeInTheDocument();
    expect(screen.getByText("btn:e1")).toBeInTheDocument();
  });

  it("renders only the base cell when the target is null", () => {
    mockButton = ({ target }) => <span>btn:{target.entityId}</span>;
    const Cell = withExplain(Base, buildTarget);
    render(Cell(ctx({ id: "e1" })) as JSX.Element);
    expect(screen.getByText("base")).toBeInTheDocument();
    expect(screen.queryByText(/^btn:/)).not.toBeInTheDocument();
  });

  it("renders only the base cell with no plugin button (OSS)", () => {
    mockButton = null;
    const Cell = withExplain(Base, buildTarget);
    render(Cell(ctx({ id: "e1", project_id: "p1" })) as JSX.Element);
    expect(screen.getByText("base")).toBeInTheDocument();
  });
});
