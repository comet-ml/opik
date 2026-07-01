import React, { ComponentProps } from "react";

import { Separator } from "@/ui/separator";
import SearchInput from "@/shared/SearchInput/SearchInput";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import OptimizationsActionsPanel from "@/v2/pages/OptimizationsPage/OptimizationsActionsPanel/OptimizationsActionsPanel";
import { ColumnData } from "@/types/shared";
import { Optimization } from "@/types/optimizations";
import { FILTER_COLUMNS } from "@/v2/pages/OptimizationsPage/OptimizationsColumns";
import { ITEM_SOURCE_LABEL } from "@/v2/pages-shared/experiments/ItemSourceCell";

type OptimizationsToolbarProps = {
  search: string;
  onSearchChange: ComponentProps<typeof SearchInput>["setSearchText"];
  filters: ComponentProps<typeof FiltersButton>["filters"];
  onFiltersChange: ComponentProps<typeof FiltersButton>["onChange"];
  // Loosely typed; `as never` at the call site, matching the filter-config convention.
  filtersConfig: object;
  canViewDatasets: boolean;
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
  filters,
  onFiltersChange,
  filtersConfig,
  canViewDatasets,
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
    <div className="mb-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
      <div className="flex items-center gap-2">
        <SearchInput
          searchText={search}
          setSearchText={onSearchChange}
          placeholder={`Search by ${ITEM_SOURCE_LABEL.toLowerCase()}`}
          className="w-[320px]"
          dimension="sm"
        />
        {canViewDatasets && (
          <FiltersButton
            columns={FILTER_COLUMNS}
            config={filtersConfig as never}
            filters={filters}
            onChange={onFiltersChange}
            layout="icon"
          />
        )}
      </div>
      <div className="flex items-center gap-2">
        {canDeleteOptimizationRuns && (
          <>
            <OptimizationsActionsPanel optimizations={selectedRows} />
            <Separator orientation="vertical" className="mx-2 h-4" />
          </>
        )}
        <RefreshButton
          tooltip="Refresh optimizations list"
          isFetching={isFetching}
          onRefresh={onRefresh}
        />
        <ColumnsButton
          columns={columns}
          selectedColumns={selectedColumns}
          onSelectionChange={onSelectedColumnsChange}
          order={columnsOrder}
          onOrderChange={onColumnsOrderChange}
        />
      </div>
    </div>
  );
};

export default OptimizationsToolbar;
