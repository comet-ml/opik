import React, { useMemo, useState } from "react";
import toLower from "lodash/toLower";
import difference from "lodash/difference";

import { Checkbox } from "@/components/ui/checkbox";
import { Separator } from "@/components/ui/separator";
import {
  DropdownMenuCustomCheckboxItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu";
import SortableMenuSection from "./SortableMenuSection";
import { ColumnData } from "@/types/shared";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import type { ColumnsContentVariant } from "./SortableMenuItem";

type ColumnsContentShared<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  order: string[];
  onOrderChange: (order: string[]) => void;
};

export type ColumnsContentExtraSection<TColumnData> = {
  title: string;
} & ColumnsContentShared<TColumnData>;

export type ColumnsContentProps<TColumnData> = {
  selectedColumns: string[];
  onSelectionChange: (selectedColumns: string[]) => void;
  sections?: ColumnsContentExtraSection<TColumnData>[];
  excludeFromSelectAll?: string[];
  variant?: ColumnsContentVariant;
  listMaxHeight?: string;
} & ColumnsContentShared<TColumnData>;

const ColumnsContent = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
  sections,
  excludeFromSelectAll = [],
  variant = "list",
  listMaxHeight,
}: ColumnsContentProps<TColumnData>) => {
  const [search, setSearch] = useState("");

  const allColumnsIds = useMemo(
    () =>
      [{ columns: columns }, ...(sections || [])].flatMap(
        ({ columns: columnGroup = [] }) =>
          columnGroup.map((column) => column.id),
      ),
    [columns, sections],
  );

  const selectAllColumnsIds = useMemo(
    () => difference(allColumnsIds, excludeFromSelectAll),
    [allColumnsIds, excludeFromSelectAll],
  );

  const allColumnsSelected = useMemo(() => {
    return (
      selectAllColumnsIds.length > 0 &&
      selectAllColumnsIds.every((id) => selectedColumns.includes(id))
    );
  }, [selectedColumns, selectAllColumnsIds]);

  const toggleColumns = (value: boolean) => {
    if (value) {
      const currentlySelectedMetadataFields = selectedColumns.filter((id) =>
        excludeFromSelectAll.includes(id),
      );
      onSelectionChange([
        ...selectAllColumnsIds,
        ...currentlySelectedMetadataFields,
      ]);
    } else {
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

  const selectedCount = selectedColumns.filter(
    (id) => !excludeFromSelectAll.includes(id),
  ).length;
  const totalCount = selectAllColumnsIds.length;

  const isMenu = variant === "menu";

  const renderSectionSeparator = (key: string) => {
    if (isMenu) {
      return <DropdownMenuSeparator key={key} />;
    }
    return <Separator key={key} className="-mx-px my-1" />;
  };

  const renderSectionLabel = (title: string) => {
    if (isMenu) {
      return (
        <DropdownMenuLabel key={`label-${title}`}>{title}</DropdownMenuLabel>
      );
    }
    return (
      <div
        key={`label-${title}`}
        className="comet-body-s-accented min-h-10 px-4 py-2.5"
      >
        {title}
      </div>
    );
  };

  const renderSelectAll = () => {
    if (isMenu) {
      return (
        <DropdownMenuCustomCheckboxItem
          checked={allColumnsSelected}
          onCheckedChange={toggleColumns}
          onSelect={(event) => event.preventDefault()}
        >
          <div className="w-full break-words py-2">
            {selectedCount} of {totalCount} selected
          </div>
        </DropdownMenuCustomCheckboxItem>
      );
    }
    return (
      <div
        className="comet-body-s relative flex min-h-10 cursor-pointer select-none items-center break-all rounded-sm pl-10 pr-4 outline-none transition-colors hover:bg-primary-foreground hover:text-foreground"
        onClick={() => toggleColumns(!allColumnsSelected)}
      >
        <span className="absolute left-2 flex size-8 items-center justify-center">
          <Checkbox
            checked={
              allColumnsSelected
                ? true
                : selectedCount > 0
                  ? "indeterminate"
                  : false
            }
            tabIndex={-1}
          />
        </span>
        <div className="w-full break-words py-2">
          {selectedCount} of {totalCount} selected
        </div>
      </div>
    );
  };

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
          variant={variant}
        />
        {filteredSections.length > 0 &&
          filteredSections.map((section, index) => {
            if (section.columns.length === 0) return null;
            const isFirst = index === 0;
            const hasTitle = section.title && section.title.trim().length > 0;
            const sectionKey = hasTitle
              ? section.title
              : section.columns
                  .map((col) => col.id)
                  .sort()
                  .join("-");

            return (
              <React.Fragment key={`fragment-${sectionKey}`}>
                {!(isFirst && filteredColumns.length === 0) &&
                  renderSectionSeparator(`separator-${sectionKey}`)}
                {hasTitle && renderSectionLabel(section.title)}
                <SortableMenuSection
                  key={`sortable-section-${sectionKey}`}
                  columns={section.columns}
                  selectedColumns={selectedColumns}
                  onSelectionChange={onSelectionChange}
                  order={section.order}
                  onOrderChange={section.onOrderChange}
                  disabledSorting={Boolean(search)}
                  variant={variant}
                />
              </React.Fragment>
            );
          })}
      </>
    );
  };

  return (
    <>
      <div onKeyDown={(e) => e.stopPropagation()}>
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search"
          variant="ghost"
        />
        <Separator className="mt-1" />
      </div>
      <div
        className="overflow-y-auto p-1"
        style={{ maxHeight: listMaxHeight ?? "calc(50vh - 3.5rem)" }}
      >
        {renderContent()}
      </div>
      {!noData && (
        <>
          <Separator />
          <div className="p-1">{renderSelectAll()}</div>
        </>
      )}
    </>
  );
};

export default ColumnsContent;
