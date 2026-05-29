import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";

/**
 * Shared placeholder shown in a table cell when its value is empty.
 * Use the constant for inline renders (e.g. inside an existing CellWrapper)
 * and the component for empty early-returns that need their own CellWrapper.
 */
export const EMPTY_CELL_PLACEHOLDER = "-";

/** Whether a string-typed cell value should render the empty placeholder. */
export const isCellValueEmpty = (value: unknown): boolean =>
  value === null || value === undefined || value === "";

type EmptyCellPlaceholderProps<TData, TValue> = {
  context: CellContext<TData, TValue>;
  className?: string;
};

const EmptyCellPlaceholder = <TData, TValue>({
  context,
  className,
}: EmptyCellPlaceholderProps<TData, TValue>) => (
  <CellWrapper
    metadata={context.column.columnDef.meta}
    tableMetadata={context.table.options.meta}
    className={className}
  >
    {EMPTY_CELL_PLACEHOLDER}
  </CellWrapper>
);

export default EmptyCellPlaceholder;
