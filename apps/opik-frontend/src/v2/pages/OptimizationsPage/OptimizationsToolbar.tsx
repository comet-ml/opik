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
    // Search + right-aligned actions share the top row; the filter bar sits
    // inline on wide screens (md+) but wraps to its own full-width line below
    // the search on narrower screens, while the actions stay aligned with
    // search on top.
    <div className="mb-2 flex flex-wrap items-center gap-x-8 gap-y-2">
      <SearchInput
        searchText={search}
        setSearchText={onSearchChange}
        placeholder="Search"
        className="order-1 w-[200px] shrink-0"
        dimension="xs"
      />
      <div className="order-2 ml-auto flex items-center gap-2 md:order-3">
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
      <div className="order-3 w-full min-w-0 md:order-2 md:w-auto md:flex-1">
        <FilterChipBar {...filterChips} />
      </div>
    </div>
  );
};

export default OptimizationsToolbar;
