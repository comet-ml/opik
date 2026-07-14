import React, { ComponentProps } from "react";

import { Separator } from "@/ui/separator";
import SearchInput from "@/shared/SearchInput/SearchInput";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import FilterChipBar, {
  FilterChipBarProps,
} from "@/shared/filter-chips/FilterChipBar/FilterChipBar";
import OptimizationsActionsPanel from "@/v2/pages/OptimizationsPage/OptimizationsActionsPanel/OptimizationsActionsPanel";
import { ColumnData } from "@/types/shared";
import { Optimization } from "@/types/optimizations";

type OptimizationsToolbarProps = {
  search: string;
  onSearchChange: ComponentProps<typeof SearchInput>["setSearchText"];
  filterChips: Omit<FilterChipBarProps, "prefix">;
  canDeleteOptimizationRuns: boolean;
  selectedRows: Optimization[];
  isFetching: boolean;
  onRefresh: () => void;
  columns: ColumnData<Optimization>[];
  selectedColumns: string[];
  onSelectedColumnsChange: ComponentProps<
    typeof ColumnsButton
  >["onSelectionChange"];
  columnsOrder: string[];
  onColumnsOrderChange: ComponentProps<typeof ColumnsButton>["onOrderChange"];
};

const OptimizationsToolbar: React.FC<OptimizationsToolbarProps> = ({
  search,
  onSearchChange,
  filterChips,
  canDeleteOptimizationRuns,
  selectedRows,
  isFetching,
  onRefresh,
  columns,
  selectedColumns,
  onSelectedColumnsChange,
  columnsOrder,
  onColumnsOrderChange,
}) => {
  return (
    // Single row: the search (FilterChipBar prefix) and filter chips fill the
    // left and wrap to further lines as they grow; the actions (delete /
    // columns / refresh) stay right-aligned, top-aligned with the first line.
    <div className="mb-2 flex items-start gap-2">
      <div className="min-w-0 flex-1">
        <FilterChipBar
          {...filterChips}
          prefix={
            <SearchInput
              searchText={search}
              setSearchText={onSearchChange}
              placeholder="Search"
              className="w-[200px] shrink-0"
              dimension="xs"
            />
          }
        />
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {canDeleteOptimizationRuns && (
          <>
            <OptimizationsActionsPanel optimizations={selectedRows} />
            <Separator orientation="vertical" className="mx-[2px] h-4" />
          </>
        )}
        <ColumnsButton
          columns={columns}
          selectedColumns={selectedColumns}
          onSelectionChange={onSelectedColumnsChange}
          order={columnsOrder}
          onOrderChange={onColumnsOrderChange}
          layout="labeled"
          size="2xs"
        />
        <Separator orientation="vertical" className="mx-[2px] h-4" />
        <RefreshButton
          tooltip="Refresh optimizations list"
          isFetching={isFetching}
          onRefresh={onRefresh}
          size="icon-2xs"
        />
      </div>
    </div>
  );
};

export default OptimizationsToolbar;
