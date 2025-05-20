import React, { useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { RowSelectionState } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import get from "lodash/get";
import isObject from "lodash/isObject";

import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import Loader from "@/components/shared/Loader/Loader";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";

import { COLUMN_TYPE } from "@/types/shared";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import CommitsActionsPanel from "@/components/pages/PromptPage/CommitsTab/CommitsActionsPanel";

export const getRowId = (p: PromptVersion) => p.id;

interface CommitsTabInterface {
  prompt?: PromptWithLatestVersion;
}

const PAGINATION_SIZE_KEY = "prompt-commits-pagination-size";

export const COMMITS_DEFAULT_COLUMNS = [
  generateSelectColumDef<PromptVersion>(),
  mapColumnDataFields<PromptVersion, PromptVersion>({
    id: "commit",
    label: "Prompt commit",
    type: COLUMN_TYPE.string,
    cell: ResourceCell as never,
    customMeta: {
      nameKey: "commit",
      idKey: "prompt_id",
      resource: RESOURCE_TYPE.prompt,
      getSearch: (data: PromptVersion) => ({
        activeVersionId: get(data, "id", null),
      }),
    },
  }),
  ...convertColumnDataToColumn<PromptVersion, PromptVersion>(
    [
      {
        id: "template",
        label: "Prompt",
        type: COLUMN_TYPE.dictionary,
        cell: CodeCell as never,
      },
      {
        id: "metadata",
        label: "Metadata",
        type: COLUMN_TYPE.dictionary,
        accessorFn: (row) =>
          isObject(row.metadata)
            ? JSON.stringify(row.metadata, null, 2)
            : row.metadata,
        cell: CodeCell as never,
      },
      {
        id: "change_description",
        label: "Commit message",
        type: COLUMN_TYPE.string,
      },
      {
        id: "created_at",
        label: "Created at",
        type: COLUMN_TYPE.time,
        accessorFn: (row) => formatDate(row.created_at),
      },
      {
        id: "created_by",
        label: "Created by",
        type: COLUMN_TYPE.string,
      },
    ],
    {},
  ),
];

const CommitsTab = ({ prompt }: CommitsTabInterface) => {
  const [page, setPage] = useState(1);
  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending } = usePromptVersionsById(
    {
      promptId: prompt?.id || "",
      page: page,
      size: size,
    },
    {
      enabled: !!prompt?.id,
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    },
  );

  const versions = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noDataText = "There are no commits yet";

  const selectedRows: PromptVersion[] = useMemo(() => {
    return versions.filter((row) => rowSelection[row.id]);
  }, [rowSelection, versions]);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div>
      <div className="mb-4 flex items-center justify-end">
        <div className="flex items-center gap-2">
          <CommitsActionsPanel versions={selectedRows} />
        </div>
      </div>
      <DataTable
        columns={COMMITS_DEFAULT_COLUMNS}
        data={versions}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        noData={<DataTableNoData title={noDataText} />}
      />
      <div className="py-4">
        <DataTablePagination
          page={page}
          pageChange={setPage}
          size={size}
          sizeChange={setSize}
          total={total}
        />
      </div>
    </div>
  );
};

export default CommitsTab;
