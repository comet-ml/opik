import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";
import { Book } from "lucide-react";
import get from "lodash/get";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import Loader from "@/components/shared/Loader/Loader";
import AddExperimentDialog from "@/components/pages/ExperimentsShared/AddExperimentDialog";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import { COLUMN_NAME_ID, COLUMN_SELECT_ID, COLUMN_TYPE } from "@/types/shared";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Experiment } from "@/types/datasets";
import { convertColumnDataToColumn } from "@/lib/table";
import { buildDocsUrl } from "@/lib/utils";
import { formatDate } from "@/lib/date";

const COLUMNS_WIDTH_KEY = "home-experiments-columns-width";

export const COLUMNS = convertColumnDataToColumn<Experiment, Experiment>(
  [
    {
      id: COLUMN_NAME_ID,
      label: "Experiment",
      type: COLUMN_TYPE.string,
      cell: ResourceCell as never,
      sortable: true,
      customMeta: {
        nameKey: "name",
        idKey: "dataset_id",
        resource: RESOURCE_TYPE.experiment,
        getSearch: (data: Experiment) => ({
          experiments: [data.id],
        }),
      },
    },
    {
      id: "dataset",
      label: "Dataset",
      type: COLUMN_TYPE.string,
      cell: ResourceCell as never,
      customMeta: {
        nameKey: "dataset_name",
        idKey: "dataset_id",
        resource: RESOURCE_TYPE.dataset,
      },
    },
    {
      id: "prompt",
      label: "Prompt commit",
      type: COLUMN_TYPE.string,
      cell: ResourceCell as never,
      customMeta: {
        nameKey: "prompt_version.commit",
        idKey: "prompt_version.prompt_id",
        resource: RESOURCE_TYPE.prompt,
        getSearch: (data: Experiment) => ({
          activeVersionId: get(data, "prompt_version.id", null),
        }),
      },
    },
    {
      id: "trace_count",
      label: "Trace count",
      type: COLUMN_TYPE.number,
    },
    {
      id: "created_at",
      label: "Created",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.created_at),
      sortable: true,
    },
    {
      id: "last_updated_at",
      label: "Last updated",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.last_updated_at),
      sortable: true,
    },
  ],
  {},
);

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const EvaluationSection: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const { data, isPending } = useExperimentsList(
    {
      workspaceName,
      page: 1,
      size: 5,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const experiments = useMemo(() => data?.content ?? [], [data?.content]);
  const noDataText = "There are no experiments yet";

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleNewExperimentClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pb-4">
      <div className="flex items-center justify-between gap-8 pb-4 pt-2">
        <div className="flex items-center gap-2">
          <h2 className="comet-body-accented truncate break-words">
            Evaluation
          </h2>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" asChild>
            <a
              href={buildDocsUrl("/evaluation/concepts")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4 shrink-0" />
              Learn more
            </a>
          </Button>
          <Link to="/$workspaceName/experiments" params={{ workspaceName }}>
            <Button variant="outline">View all experiments</Button>
          </Link>
        </div>
      </div>
      <DataTable
        columns={COLUMNS}
        data={experiments}
        resizeConfig={resizeConfig}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            <Button variant="link" onClick={handleNewExperimentClick}>
              Create new experiment
            </Button>
          </DataTableNoData>
        }
      />
      <AddExperimentDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default EvaluationSection;
