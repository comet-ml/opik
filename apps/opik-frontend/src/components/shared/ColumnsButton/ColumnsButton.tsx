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
} & ColumnsButtonShared<TColumnData>;

const ColumnsButton = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
  sections,
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

  const allColumnsSelected = useMemo(() => {
    return selectedColumns.length === allColumnsIds.length;
  }, [selectedColumns, allColumnsIds]);

  const toggleColumns = (value: boolean) => {
    onSelectionChange(value ? allColumnsIds : []);
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

            return (
              <React.Fragment key={`fragment-${section.title}`}>
                {!(isFirst && filteredColumns.length === 0) && (
                  <DropdownMenuSeparator key={`separator-${section.title}`} />
                )}
                <DropdownMenuLabel key={`label-${section.title}`}>
                  {section.title}
                </DropdownMenuLabel>
                <SortableMenuSection
                  key={`sortable-section-${section.title}`}
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
