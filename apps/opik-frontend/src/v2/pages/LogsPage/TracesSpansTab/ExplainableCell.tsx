import { ReactNode } from "react";
import { CellContext, flexRender } from "@tanstack/react-table";
import usePluginsStore from "@/store/PluginsStore";
import { BaseTraceData } from "@/types/traces";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { ExplainableRow } from "./explainTargets";

type BaseCell = (context: CellContext<BaseTraceData, never>) => ReactNode;
type BuildTarget = (row: ExplainableRow) => ExplainTarget | null;

/**
 * Wrap a Traces-table cell so the Ollie Explain button appears (on cell-content
 * hover) at the cell's right edge. `buildTarget` derives the explain payload
 * from the row; when it returns null (no value worth explaining, or OSS without
 * the plugin button) only the base cell renders. The button positions/reveals
 * itself against this `group/cell` root. Plugin-scoping: core only reads the
 * PluginsStore slot — it never imports the plugin store.
 */
export const withExplain = (BaseCell: BaseCell, buildTarget: BuildTarget) => {
  const ExplainableCell = (
    context: CellContext<BaseTraceData, never>,
  ): ReactNode => {
    // The slot is set once during setupPlugins() (before any render) and never
    // mutates, so read it directly rather than subscribing per rendered row.
    const ExplainButton = usePluginsStore.getState().ExplainButton;
    const target = buildTarget(context.row.original as ExplainableRow);

    return (
      <div className="group/cell relative flex size-full items-center">
        {/* flexRender (not BaseCell(context)) so the cell mounts as its own
            fiber — keeps any hooks a base cell may use inside ITS component, not
            ExplainableCell's. */}
        {flexRender(BaseCell, context)}
        {ExplainButton && target ? <ExplainButton target={target} /> : null}
      </div>
    );
  };
  // Disambiguate the three wrapped cells in React DevTools / error stacks.
  (ExplainableCell as { displayName?: string }).displayName = `withExplain(${
    BaseCell.name || "Cell"
  })`;
  return ExplainableCell;
};
