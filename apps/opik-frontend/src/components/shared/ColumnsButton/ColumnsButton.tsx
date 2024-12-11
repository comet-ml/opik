import React, { useCallback, useMemo, useState } from "react";
import { Columns3, Eye, EyeOff } from "lucide-react";
import toLower from "lodash/toLower";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
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

  const getAllColumnsIds = () =>
    (sections || []).reduce<string[]>(
      (acc, { columns = [] }) => acc.concat(columns.map((c) => c.id)),
      columns.map((c) => c.id),
    );

  const toggleColumns = (value: boolean) => {
    onSelectionChange(value ? getAllColumnsIds() : []);
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
      <div className="min-w-56 max-w-72 overflow-hidden">
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
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => toggleColumns(true)}>
          <Eye className="mr-2 size-4" />
          Show all
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => toggleColumns(false)}>
          <EyeOff className="mr-2 size-4" />
          Hide all
        </DropdownMenuItem>
      </div>
    );
  };

  return (
    <DropdownMenu onOpenChange={openStateChangeHandler}>
      <DropdownMenuTrigger asChild>
        <Button variant="secondary">
          <Columns3 className="mr-2 size-4" />
          Columns
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="pt-12" align="end">
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
        <div className="max-h-[50vh] overflow-y-auto">{renderContent()}</div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ColumnsButton;
