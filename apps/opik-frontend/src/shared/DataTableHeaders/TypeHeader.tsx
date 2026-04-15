import { useCallback, useRef, useState } from "react";
import { HeaderContext } from "@tanstack/react-table";
import { Checkbox } from "@/ui/checkbox";
import HeaderWrapper from "@/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/shared/DataTableHeaders/useSortableHeader";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

const TypeHeader = <TData,>(context: HeaderContext<TData, unknown>) => {
  const { column } = context;
  const { header, headerCheckbox, explainer } = column.columnDef.meta ?? {};

  const { className, onClickHandler, renderSort, isSorted, isSortable } =
    useSortableHeader({
      column,
      withSeparator: Boolean(explainer),
    });

  const textRef = useRef<HTMLSpanElement>(null);
  const [isTruncated, setIsTruncated] = useState(false);

  const checkTruncation = useCallback(() => {
    const el = textRef.current;
    if (el) {
      setIsTruncated(el.scrollWidth > el.clientWidth);
    }
  }, []);

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={className}
      onClick={onClickHandler}
    >
      {headerCheckbox && (
        <Checkbox
          variant="muted"
          className="mr-3.5"
          onClick={(event) => event.stopPropagation()}
          checked={
            context.table.getIsAllPageRowsSelected() ||
            (context.table.getIsSomePageRowsSelected() && "indeterminate")
          }
          onCheckedChange={(value) =>
            context.table.toggleAllPageRowsSelected(!!value)
          }
          aria-label="Select all"
        />
      )}
      <TooltipWrapper content={isTruncated ? header : null}>
        <span
          ref={textRef}
          className={cn(
            "truncate",
            isSortable && isSorted && "text-foreground-secondary",
          )}
          onMouseEnter={checkTruncation}
        >
          {header}
        </span>
      </TooltipWrapper>
      {explainer && (
        <ExplainerIcon
          {...explainer}
          className={cn(isSortable && isSorted && "text-foreground-secondary")}
        />
      )}
      {renderSort()}
    </HeaderWrapper>
  );
};

export default TypeHeader;
