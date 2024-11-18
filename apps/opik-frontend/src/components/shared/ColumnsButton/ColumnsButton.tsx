import React from "react";
import { Columns3 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import SortableMenuSection from "./SortableMenuSection";
import { ColumnData } from "@/types/shared";

type ColumnsButtonShared<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  order: string[];
  onOrderChange: (order: string[]) => void;
};

type ColumnsButtonExtraButton<TColumnData> = {
  title: string;
} & ColumnsButtonShared<TColumnData>;

export type ColumnsButtonProps<TColumnData> = {
  selectedColumns: string[];
  onSelectionChange: (selectedColumns: string[]) => void;
  extraSection?: ColumnsButtonExtraButton<TColumnData>;
} & ColumnsButtonShared<TColumnData>;

const ColumnsButton = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
  extraSection,
}: ColumnsButtonProps<TColumnData>) => {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="secondary">
          <Columns3 className="mr-2 size-4" />
          Columns
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="max-h-[60vh] w-56 overflow-y-auto">
        <SortableMenuSection
          columns={columns}
          selectedColumns={selectedColumns}
          onSelectionChange={onSelectionChange}
          order={order}
          onOrderChange={onOrderChange}
        />
        {extraSection && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuLabel>{extraSection.title}</DropdownMenuLabel>
            <SortableMenuSection
              columns={extraSection.columns}
              selectedColumns={selectedColumns}
              onSelectionChange={onSelectionChange}
              order={extraSection.order}
              onOrderChange={extraSection.onOrderChange}
            />
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ColumnsButton;
