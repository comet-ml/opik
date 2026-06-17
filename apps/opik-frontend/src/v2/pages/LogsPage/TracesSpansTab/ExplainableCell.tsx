import { ReactNode } from "react";
import { CellContext } from "@tanstack/react-table";
import usePluginsStore from "@/store/PluginsStore";
import { BaseTraceData } from "@/types/traces";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { ExplainableRow } from "./explainTargets";

type BaseCell = (context: CellContext<BaseTraceData, never>) => ReactNode;
type BuildTarget = (row: ExplainableRow) => ExplainTarget | null;

/**
 * Wrap a Traces-table cell so the Ollie Explain button appears (on cell-content
 * hover) at the cell's right edge. `buildTarget` derives the explain payload
 * from the row; when it returns null (no value, or OSS without the plugin
 * button) only the base cell renders. The button positions/reveals itself
 * against this `group/cell` root. Plugin-scoping: core only reads the
 * PluginsStore slot — it never imports the plugin store.
 */
export const withExplain = (BaseCell: BaseCell, buildTarget: BuildTarget) => {
  const ExplainableCell = (
    context: CellContext<BaseTraceData, never>,
  ): ReactNode => {
    const ExplainButton = usePluginsStore((s) => s.ExplainButton);
    const target = buildTarget(context.row.original as ExplainableRow);

    return (
      <div className="group/cell relative flex size-full items-center">
        {BaseCell(context)}
        {ExplainButton && target ? <ExplainButton target={target} /> : null}
      </div>
    );
  };
  return ExplainableCell;
};
