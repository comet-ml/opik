import React, { useCallback, useMemo, useState } from "react";
import { Columns3 } from "lucide-react";
import toLower from "lodash/toLower";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuCustomCheckboxItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import SortableMenuSection from "./SortableMenuSection";
import { ColumnData } from "@/types/shared";
import SearchInput from "@/components/shared/SearchInput/SearchInput";

/**
 * Computes the list of column IDs that should be selected when "Select all" is checked.
 * Excludes columns specified in excludeFromSelectAll.
 *
 * @param allColumnsIds - All available column IDs
 * @param excludeFromSelectAll - Column IDs to exclude from select all
 * @returns Filtered list of column IDs for select all functionality
 */
export function computeSelectAllColumnsIds(
  allColumnsIds: string[],
  excludeFromSelectAll: string[],
): string[] {
  return allColumnsIds.filter((id) => !excludeFromSelectAll.includes(id));
}

type ColumnsButtonShared<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  order: string[];
  onOrderChange: (order: string[]) => void;
};

type ColumnsButtonExtraSection<TColumnData> = {
  title: string;
} & ColumnsButtonShared<TColumnData>;

export type ColumnsButtonProps<TColumnData> = {
  selectedColumns: string[];
  onSelectionChange: (selectedColumns: string[]) => void;
  sections?: ColumnsButtonExtraSection<TColumnData>[];
  excludeFromSelectAll?: string[]; // Column IDs to exclude when selecting all (but include when deselecting all)
} & ColumnsButtonShared<TColumnData>;

const ColumnsButton = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
  sections,
  excludeFromSelectAll = [],
}: ColumnsButtonProps<TColumnData>) => {
  const [search, setSearch] = useState("");

  const allColumnsIds = useMemo(
    () =>
      [{ columns: columns }, ...(sections || [])].flatMap(
        ({ columns: columnGroup = [] }) =>
          columnGroup.map((column) => column.id),
      ),
    [columns, sections],
  );

  // Columns to select when "Select all" is checked (excludes metadata fields)
  const selectAllColumnsIds = useMemo(
    () => computeSelectAllColumnsIds(allColumnsIds, excludeFromSelectAll),
    [allColumnsIds, excludeFromSelectAll],
  );

  const allColumnsSelected = useMemo(() => {
    // Check if all non-excluded columns are selected
    return (
      selectAllColumnsIds.length > 0 &&
      selectAllColumnsIds.every((id) => selectedColumns.includes(id))
    );
  }, [selectedColumns, selectAllColumnsIds]);

  const toggleColumns = (value: boolean) => {
    if (value) {
      // Selecting all: select all non-metadata columns + preserve any already-selected metadata fields
      const currentlySelectedMetadataFields = selectedColumns.filter((id) =>
        excludeFromSelectAll.includes(id),
      );
      onSelectionChange([
        ...selectAllColumnsIds,
        ...currentlySelectedMetadataFields,
      ]);
    } else {
      // Deselecting all: clear everything including metadata fields
      onSelectionChange([]);
    }
  };

  const filteredColumns = useMemo(() => {
    return columns.filter((c) => toLower(c.label).includes(toLower(search)));
  }, [columns, search]);

  const filteredSections = useMemo(() => {
    return (sections || []).map((section) => {
      return {
        ...section,
        columns: section.columns.filter((c) =>
          toLower(c.label).includes(toLower(search)),
        ),
      };
    });
  }, [search, sections]);

  const noData = useMemo(
    () =>
      filteredColumns.length === 0 &&
      filteredSections.every(({ columns = [] }) => columns.length === 0),
    [filteredColumns, filteredSections],
  );

  const openStateChangeHandler = useCallback((open: boolean) => {
    // need to clear state when it closed
    if (!open) setSearch("");
  }, []);

  const renderContent = () => {
    if (noData) {
      return (
        <div className="comet-body-s flex h-32 w-56 items-center justify-center text-muted-slate">
          No search results
        </div>
      );
    }

    return (
      <>
        <SortableMenuSection
          columns={filteredColumns}
          selectedColumns={selectedColumns}
          onSelectionChange={onSelectionChange}
          order={order}
          onOrderChange={onOrderChange}
          disabledSorting={Boolean(search)}
        />
        {filteredSections.length > 0 &&
          filteredSections.map((section, index) => {
            if (section.columns.length === 0) return null;
            const isFirst = index === 0;
            const hasTitle = section.title && section.title.trim().length > 0;
            // Generate a stable key: use title if available, otherwise use column IDs
            // Sort column IDs for stability regardless of order
            const sectionKey = hasTitle
              ? section.title
              : section.columns
                  .map((col) => col.id)
                  .sort()
                  .join("-");

            return (
              <React.Fragment key={`fragment-${sectionKey}`}>
                {!(isFirst && filteredColumns.length === 0) && (
                  <DropdownMenuSeparator key={`separator-${sectionKey}`} />
                )}
                {hasTitle && (
                  <DropdownMenuLabel key={`label-${section.title}`}>
                    {section.title}
                  </DropdownMenuLabel>
                )}
                <SortableMenuSection
                  key={`sortable-section-${sectionKey}`}
                  columns={section.columns}
                  selectedColumns={selectedColumns}
                  onSelectionChange={onSelectionChange}
                  order={section.order}
                  onOrderChange={section.onOrderChange}
                  disabledSorting={Boolean(search)}
                />
              </React.Fragment>
            );
          })}
      </>
    );
  };

  return (
    <DropdownMenu onOpenChange={openStateChangeHandler}>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm">
          <Columns3 className="mr-1.5 size-3.5" />
          Columns
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="min-w-56 max-w-72 p-0 pt-12" align="end">
        <div
          className="absolute inset-x-1 top-1 h-11"
          onKeyDown={(e) => e.stopPropagation()}
        >
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder="Search"
            variant="ghost"
          ></SearchInput>
          <Separator className="mt-1" />
        </div>
        <div className="max-h-[calc(50vh-3.5rem)] overflow-y-auto p-1">
          {renderContent()}
        </div>
        {!noData && (
          <>
            <Separator />
            <div className="p-1">
              <DropdownMenuCustomCheckboxItem
                checked={allColumnsSelected}
                onCheckedChange={toggleColumns}
                onSelect={(event) => event.preventDefault()}
              >
                <div className="w-full break-words py-2">Select all</div>
              </DropdownMenuCustomCheckboxItem>
            </div>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ColumnsButton;
