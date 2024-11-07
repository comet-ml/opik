import React, { useCallback, useState } from "react";

import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import Loader from "@/components/shared/Loader/Loader";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";

import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";

import { COLUMN_TYPE } from "@/types/shared";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn } from "@/lib/table";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";

interface CommitsTabInterface {
  prompt?: PromptWithLatestVersion;
}

export const COMMITS_DEFAULT_COLUMNS = convertColumnDataToColumn<
  PromptVersion,
  PromptVersion
>(
  [
    {
      id: "id",
      label: "Prompt commit",
      type: COLUMN_TYPE.string,
      cell: IdCell as never,
    },
    {
      id: "template",
      label: "Prompt",
      type: COLUMN_TYPE.dictionary,
      cell: CodeCell as never,
    },

    {
      id: "created_at",
      label: "Created at",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.created_at),
    },
  ],
  {},
);

const CommitsTab = ({ prompt }: CommitsTabInterface) => {
  const navigate = useNavigate();

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const { data, isPending } = usePromptVersionsById(
    {
      promptId: prompt?.id || "",
      page: page,
      size: size,
    },
    {
      enabled: !!prompt?.id,
    },
  );

  const versions = data?.content ?? [];
  const total = data?.total ?? 0;
  const noDataText = "There are no commits yet";

  const handleRowClick = useCallback(
    (version: PromptVersion) => {
      if (prompt?.id) {
        navigate({
          to: `/$workspaceName/prompts/$promptId`,
          params: {
            promptId: prompt.id,
            workspaceName,
          },
          search: {
            activeVersionId: version.id,
          },
        });
      }
    },
    [prompt?.id, navigate, workspaceName],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div>
      <DataTable
        columns={COMMITS_DEFAULT_COLUMNS}
        data={versions}
        onRowClick={handleRowClick}
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
