import { Column } from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, ChevronsUpDownIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

interface DataTableSortableHeaderProps<TData> {
  column: Column<TData>;
  title: string;
}

export function DataTableSortableHeader<TData>({
  column,
  title,
}: DataTableSortableHeaderProps<TData>) {
  if (!column.getCanSort()) {
    return <div className="font-medium">{title}</div>;
  }

  const handleSort = () => {
    const currentSort = column.getIsSorted();
    if (currentSort === false) {
      column.toggleSorting(false); // First click: sort ascending
    } else if (currentSort === "asc") {
      column.toggleSorting(true); // Second click: sort descending
    } else {
      column.clearSorting(); // Third click: clear sorting
    }
  };

  return (
    <div className="flex items-center space-x-2">
      <Button
        variant="ghost"
        size="sm"
        className={cn(
          "h-8 data-[state=open]:bg-accent",
          "flex items-center gap-2 px-2 py-1.5",
          "hover:bg-muted/50",
          "font-medium",
        )}
        onClick={handleSort}
      >
        <span>{title}</span>
        {{
          asc: <ArrowUpIcon className="size-4" />,
          desc: <ArrowDownIcon className="size-4" />,
        }[column.getIsSorted() as string] ?? (
          <ChevronsUpDownIcon className="size-4 opacity-50" />
        )}
      </Button>
    </div>
  );
}
