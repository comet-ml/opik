import { ReactNode } from "react";
import { CellContext, flexRender } from "@tanstack/react-table";
import usePluginsStore from "@/store/PluginsStore";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { resolveHorizontalAlignment } from "@/constants/shared";

type BaseCell<TData> = (context: CellContext<TData, never>) => ReactNode;
type BuildTarget<TData> = (row: TData) => ExplainTarget | null;

/**
 * Wrap a data-table cell so the Ollie Explain button appears (on cell-content
 * hover) on the side of the cell opposite its value (resolved from column
 * alignment, so it never covers the value). `buildTarget` derives the explain
 * payload
 * from the row; when it returns null (the row isn't explainable — e.g. no
 * project_id, or an empty error cell — or OSS without the plugin button) only
 * the base cell renders. The button positions/reveals itself against this
 * `group/cell` root. Plugin-scoping: core only reads the PluginsStore slot —
 * it never imports the plugin store.
 *
 * Generic over the row type so the Traces/Spans tables (BaseTraceData rows) and
 * the Threads table (Thread rows) share one wrapper; the matching `buildTarget`
 * fixes `TData` per call site.
 */
export const withExplain = <TData,>(
  BaseCell: BaseCell<TData>,
  buildTarget: BuildTarget<TData>,
) => {
  const ExplainableCell = (context: CellContext<TData, never>): ReactNode => {
    // The slot is set once during setupPlugins() (before any render) and never
    // mutates, so read it directly rather than subscribing per rendered row.
    const ExplainButton = usePluginsStore.getState().ExplainButton;
    const target = buildTarget(context.row.original);
    // Resolve alignment from the SAME source the cell body uses, so the owl
    // positions itself against the value without knowing about column types.
    // Optional chaining: a minimal/synthetic CellContext may omit `column`.
    const align = resolveHorizontalAlignment(context.column?.columnDef?.meta);

    return (
      <div className="group/cell relative flex size-full items-center">
        {/* flexRender (not BaseCell(context)) so the cell mounts as its own
            fiber — keeps any hooks a base cell may use inside ITS component, not
            ExplainableCell's. */}
        {flexRender(BaseCell, context)}
        {ExplainButton && target ? (
          <ExplainButton target={target} align={align} />
        ) : null}
      </div>
    );
  };
  // Disambiguate the wrapped cells in React DevTools / error stacks.
  (ExplainableCell as { displayName?: string }).displayName = `withExplain(${
    BaseCell.name || "Cell"
  })`;
  return ExplainableCell;
};
