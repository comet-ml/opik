import React, { useCallback, useMemo, useEffect, useState } from "react";
import { CellContext, ColumnDef } from "@tanstack/react-table";
import { Trash2, ExternalLink } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import Loader from "@/components/shared/Loader/Loader";
import useEvalSuites, {
  useEvalSuiteItems,
  useEvalRuns,
  EvalSuiteItem,
  EvalRun,
  createEvalSuite,
  deleteEvalSuiteItem,
} from "@/api/config/useEvalSuites";
import RunHistoryList from "./RunHistoryList";
import RunResultsDialog from "./RunResultsDialog";

const getRowId = (row: EvalSuiteItem) => row.id;

type EvalSuitesTabProps = {
  projectId: string;
  projectName: string;
};

const EvalSuitesTab: React.FC<EvalSuitesTabProps> = ({
  projectId,
  projectName,
}) => {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [suiteId, setSuiteId] = useState<string | null>(null);
  const [selectedRun, setSelectedRun] = useState<EvalRun | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);

  // Use projectName for config service (it stores by name, not UUID)
  const { data: suites, isPending: suitesPending } = useEvalSuites({
    projectId: projectName,
  });
  const { data: items, isPending: itemsPending } = useEvalSuiteItems({
    suiteId,
  });
  const { data: runs } = useEvalRuns({ suiteId });

  // Auto-create suite if none exists, or use the first one
  useEffect(() => {
    if (suitesPending) return;

    if (suites && suites.length > 0) {
      setSuiteId(suites[0].id);
    } else {
      // Create default suite for this project
      const suiteName = `eval-suite-${projectName}`;
      createEvalSuite(suiteName, projectName)
        .then((suite) => {
          setSuiteId(suite.id);
          queryClient.invalidateQueries({ queryKey: ["eval-suites"] });
        })
        .catch(() => {
          toast({
            description: "Failed to create evaluation suite",
            variant: "destructive",
          });
        });
    }
  }, [suites, suitesPending, projectId, projectName, queryClient, toast]);

  const handleViewRun = useCallback(
    (runId: string) => {
      const run = runs?.find((r) => r.id === runId);
      if (run) {
        setSelectedRun(run);
        setDialogOpen(true);
      }
    },
    [runs]
  );

  const deleteMutation = useMutation({
    mutationFn: ({ itemId }: { itemId: string }) =>
      deleteEvalSuiteItem(suiteId!, itemId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["eval-suite-items"] });
      queryClient.invalidateQueries({ queryKey: ["eval-suites"] });
      toast({ description: "Item deleted" });
    },
    onError: () => {
      toast({ description: "Failed to delete item", variant: "destructive" });
    },
  });

  const handleDelete = useCallback(
    (itemId: string) => {
      deleteMutation.mutate({ itemId });
    },
    [deleteMutation]
  );

  const TraceIdCell = useCallback(
    (context: CellContext<EvalSuiteItem, string | null>) => {
      const value = context.getValue();
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          {value ? (
            <div className="flex items-center gap-2">
              <code className="truncate font-mono text-sm">{value}</code>
              <Button
                variant="ghost"
                size="icon-xs"
                onClick={(e) => {
                  e.stopPropagation();
                  window.open(
                    `/${projectId}/traces?traceId=${value}`,
                    "_blank"
                  );
                }}
              >
                <ExternalLink className="size-3.5" />
              </Button>
            </div>
          ) : (
            <span className="text-muted-slate">—</span>
          )}
        </CellWrapper>
      );
    },
    [projectId]
  );

  const InputDataCell = useCallback(
    (context: CellContext<EvalSuiteItem, Record<string, unknown>>) => {
      const value = context.getValue();
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          <code className="truncate font-mono text-xs">
            {JSON.stringify(value)}
          </code>
        </CellWrapper>
      );
    },
    []
  );

  const AssertionsCell = useCallback(
    (context: CellContext<EvalSuiteItem, string[]>) => {
      const assertions = context.getValue();
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          <div className="flex flex-col gap-1">
            {assertions.map((assertion, idx) => (
              <div
                key={idx}
                className="truncate rounded bg-muted px-2 py-0.5 text-xs"
              >
                {assertion}
              </div>
            ))}
          </div>
        </CellWrapper>
      );
    },
    []
  );

  const ActionsCell = useCallback(
    (context: CellContext<EvalSuiteItem, unknown>) => {
      const item = context.row.original;
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
          stopClickPropagation
        >
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => handleDelete(item.id)}
          >
            <Trash2 className="size-4 text-destructive" />
          </Button>
        </CellWrapper>
      );
    },
    [handleDelete]
  );

  const columns: ColumnDef<EvalSuiteItem>[] = useMemo(
    () => [
      {
        accessorKey: "trace_id",
        header: "Trace ID",
        cell: TraceIdCell,
        size: 300,
      },
      {
        accessorKey: "input_data",
        header: "Input",
        cell: InputDataCell,
        size: 250,
      },
      {
        accessorKey: "assertions",
        header: "Assertions",
        cell: AssertionsCell,
        size: 400,
      },
      {
        id: "actions",
        header: "",
        cell: ActionsCell,
        size: 60,
      },
    ],
    [TraceIdCell, InputDataCell, AssertionsCell, ActionsCell]
  );

  if (suitesPending || (suiteId && itemsPending)) {
    return (
      <PageBodyStickyContainer
        direction="horizontal"
        limitWidth
        className="pt-6"
      >
        <Loader />
      </PageBodyStickyContainer>
    );
  }

  const suite = suites?.find((s) => s.id === suiteId);

  return (
    <PageBodyStickyContainer direction="horizontal" limitWidth className="pt-6">
      <div className="mb-4">
        <h2 className="text-lg font-medium">Evaluation Suite</h2>
        <p className="text-sm text-muted-slate">
          {suite?.name || "Loading..."} • {items?.length ?? 0} test cases
        </p>
      </div>

      {runs && runs.length > 0 && (
        <div className="mb-6">
          <h3 className="mb-3 text-sm font-medium">Run History</h3>
          <RunHistoryList runs={runs} onViewRun={handleViewRun} />
        </div>
      )}

      <h3 className="mb-3 text-sm font-medium">Test Cases</h3>

      {!items || items.length === 0 ? (
        <DataTableNoData title="No test cases yet">
          Test cases are added when you optimize traces and choose to save them
          to the evaluation suite.
        </DataTableNoData>
      ) : (
        <DataTable
          columns={columns}
          data={items}
          getRowId={getRowId}
          noData={<DataTableNoData title="No test cases" />}
          TableWrapper={PageBodyStickyTableWrapper}
        />
      )}

      <RunResultsDialog
        run={selectedRun}
        projectId={projectId}
        open={dialogOpen}
        onOpenChange={setDialogOpen}
      />
    </PageBodyStickyContainer>
  );
};

export default EvalSuitesTab;
