import React from "react";
import { Columns3 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ColumnData } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ColumnsContent from "@/components/shared/ColumnsContent/ColumnsContent";
import type { ColumnsContentExtraSection } from "@/components/shared/ColumnsContent/ColumnsContent";

export type ColumnsButtonProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  selectedColumns: string[];
  onSelectionChange: (selectedColumns: string[]) => void;
  order: string[];
  onOrderChange: (order: string[]) => void;
  sections?: ColumnsContentExtraSection<TColumnData>[];
  excludeFromSelectAll?: string[];
};

const ColumnsButton = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
  sections,
  excludeFromSelectAll = [],
}: ColumnsButtonProps<TColumnData>) => {
  return (
    <DropdownMenu>
      <TooltipWrapper content="Columns">
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon-sm" data-testid="columns-button">
            <Columns3 className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
      </TooltipWrapper>
      <DropdownMenuContent className="min-w-56 max-w-72 p-0" align="end">
        <ColumnsContent
          columns={columns}
          selectedColumns={selectedColumns}
          onSelectionChange={onSelectionChange}
          order={order}
          onOrderChange={onOrderChange}
          sections={sections}
          excludeFromSelectAll={excludeFromSelectAll}
          variant="menu"
        />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ColumnsButton;
