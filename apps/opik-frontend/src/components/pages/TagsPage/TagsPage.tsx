import React, { useCallback, useMemo, useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import {
  StringParam,
  useQueryParam,
} from "use-query-params";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import useTagsList from "@/api/tags/useTagsList";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { Tag } from "@/types/tags";
import Loader from "@/components/shared/Loader/Loader";
import AddEditTagDialog from "@/components/pages/TagsPage/AddEditTagDialog";
import TagsActionsPanel from "@/components/pages/TagsPage/TagsActionsPanel";
import { TagRowActionsCell } from "@/components/pages/TagsPage/TagRowActionsCell";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useAppStore from "@/store/AppStore";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { formatDate } from "@/lib/date";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import {
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState, ColumnSort } from "@tanstack/react-table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Tag as TagIcon } from "lucide-react";

export const getRowId = (tag: Tag) => tag.id;

const SELECTED_COLUMNS_KEY = "tags-selected-columns";
const COLUMNS_WIDTH_KEY = "tags-columns-width";
const COLUMNS_ORDER_KEY = "tags-columns-order";
const COLUMNS_SORT_KEY = "tags-columns-sort";
const PAGINATION_SIZE_KEY = "tags-pagination-size";

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "description",
  "created_at",
  "updated_at",
];

export const DEFAULT_SORTING_COLUMNS: ColumnSort[] = [
  {
    id: "name",
    desc: false,
  },
];

const TagsPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [searchTerm, setSearchTerm] = useQueryParam("search", StringParam);

  const columnsDef: ColumnData<Tag>[] = useMemo(() => {
    return [
      {
        id: "id",
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
        sortable: true,
      },
      {
        id: "name",
        label: "Name",
        type: COLUMN_TYPE.string,
        sortable: true,
      },
      {
        id: "description",
        label: "Description",
        type: COLUMN_TYPE.string,
      },
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.date,
        accessorFn: (row) => formatDate(row.created_at),
      },
      {
        id: "updated_at",
        label: "Updated",
        type: COLUMN_TYPE.date,
        accessorFn: (row) => formatDate(row.updated_at),
      },
    ];
  }, []);

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<Record<string, number>>(
    COLUMNS_WIDTH_KEY,
    {
      defaultValue: {},
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const [columnsSort, setColumnsSort] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: DEFAULT_SORTING_COLUMNS,
    },
  );

  const [paginationSize, setPaginationSize] = useQueryParamAndLocalStorageState(
    "size",
    PAGINATION_SIZE_KEY,
    20,
  );

  const [selectedRows, setSelectedRows] = useState<Tag[]>([]);
  const [isAddEditDialogOpen, setIsAddEditDialogOpen] = useState(false);
  const [editingTag, setEditingTag] = useState<Tag | null>(null);
  const resetKeyRef = useRef(0);

  const { data: tags = [], isLoading, error } = useTagsList();

  const filteredTags = useMemo(() => {
    if (!searchTerm) return tags;
    return tags.filter((tag) =>
      tag.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (tag.description && tag.description.toLowerCase().includes(searchTerm.toLowerCase()))
    );
  }, [tags, searchTerm]);

  const columns = useMemo(
    () => [
      generateSelectColumDef<Tag>({
        selectedRows,
        setSelectedRows,
        getRowId,
      }),
      ...convertColumnDataToColumn(columnsDef, selectedColumns),
      generateActionsColumDef<Tag>({
        cell: TagRowActionsCell,
        customMeta: {
          onEdit: (tag: Tag) => {
            setEditingTag(tag);
            setIsAddEditDialogOpen(true);
          },
          onDelete: (tag: Tag) => {
            // Handle delete in the actions cell
          },
        },
      }),
    ],
    [columnsDef, selectedColumns, selectedRows, setSelectedRows],
  );

  const handleAddNew = useCallback(() => {
    setEditingTag(null);
    setIsAddEditDialogOpen(true);
  }, []);

  const handleCloseDialog = useCallback(() => {
    setIsAddEditDialogOpen(false);
    setEditingTag(null);
  }, []);

  const handleSearch = useCallback((value: string) => {
    setSearchTerm(value || undefined);
  }, [setSearchTerm]);

  if (error) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <p className="text-lg font-semibold text-red-600">Error loading tags</p>
          <p className="text-sm text-gray-600">Please try again later</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-border px-6 py-4">
        <div className="flex items-center gap-3">
          <TagIcon className="size-6 text-primary" />
          <div>
            <h1 className="text-2xl font-semibold">Tags</h1>
            <p className="text-sm text-muted-foreground">
              Manage your workspace tags
            </p>
          </div>
        </div>
        <Button onClick={handleAddNew}>
          <TagIcon className="mr-2 size-4" />
          Add Tag
        </Button>
      </div>

      <div className="flex flex-1 flex-col gap-4 p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <SearchInput
              placeholder="Search tags..."
              value={searchTerm || ""}
              onChange={handleSearch}
              className="w-80"
            />
          </div>
          <div className="flex items-center gap-2">
            <ColumnsButton
              columnsDef={columnsDef}
              selectedColumns={selectedColumns}
              setSelectedColumns={setSelectedColumns}
            />
          </div>
        </div>

        <Separator />

        {selectedRows.length > 0 && (
          <TagsActionsPanel
            selectedRows={selectedRows}
            onSuccess={() => {
              setSelectedRows([]);
              resetKeyRef.current += 1;
            }}
          />
        )}

        <div className="flex-1">
          {isLoading ? (
            <div className="flex h-full items-center justify-center">
              <Loader />
            </div>
          ) : filteredTags.length === 0 ? (
            <DataTableNoData
              title="No tags found"
              description={
                searchTerm
                  ? `No tags match "${searchTerm}". Try adjusting your search.`
                  : "Get started by creating your first tag."
              }
              action={
                !searchTerm ? (
                  <Button onClick={handleAddNew}>
                    <TagIcon className="mr-2 size-4" />
                    Add Tag
                  </Button>
                ) : undefined
              }
            />
          ) : (
            <DataTable
              data={filteredTags}
              columns={columns}
              getRowId={getRowId}
              selectedRows={selectedRows}
              setSelectedRows={setSelectedRows}
              columnsWidth={columnsWidth}
              setColumnsWidth={setColumnsWidth}
              columnsOrder={columnsOrder}
              setColumnsOrder={setColumnsOrder}
              columnsSort={columnsSort}
              setColumnsSort={setColumnsSort}
              columnPinning={DEFAULT_COLUMN_PINNING}
              paginationSize={paginationSize}
              setPaginationSize={setPaginationSize}
            />
          )}
        </div>
      </div>

      <AddEditTagDialog
        open={isAddEditDialogOpen}
        onClose={handleCloseDialog}
        tag={editingTag}
        onSuccess={handleCloseDialog}
      />
    </div>
  );
};

export default TagsPage;