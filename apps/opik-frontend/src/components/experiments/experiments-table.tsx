import { useRef } from "react";
import { useTableScroll } from "@/hooks/use-table-scroll";
import { cn } from "@/lib/utils";
import { StickyHeader } from "@/components/ui/table/sticky-header";

export function ExperimentsTable() {
  const tableRef = useRef<HTMLTableElement>(null);
  const isScrolled = useTableScroll(tableRef);

  return (
    <div className="relative overflow-auto">
      <table ref={tableRef}>
        <StickyHeader className={cn(isScrolled && "shadow-md")}>
          {/* Your existing header content */}
        </StickyHeader>
        <tbody>{/* Your existing table body content */}</tbody>
      </table>
    </div>
  );
}
