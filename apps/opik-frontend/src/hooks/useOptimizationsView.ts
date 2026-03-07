import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { RowSelectionState } from "@tanstack/react-table";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import { Optimization } from "@/types/optimizations";

const DEFAULT_PAGE_SIZE = 100;

type UseOptimizationsViewParams = {
  workspaceName: string;
  datasetId?: string;
  search?: string;
  page: number;
  rowSelection: RowSelectionState;
};

export const useOptimizationsView = ({
  workspaceName,
  datasetId,
  search,
  page,
  rowSelection,
}: UseOptimizationsViewParams) => {
  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useOptimizationsList(
      {
        workspaceName,
        datasetId: datasetId || "",
        search: search || "",
        page,
        size: DEFAULT_PAGE_SIZE,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: 30000,
      },
    );

  const optimizations = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;

  const selectedRows: Array<Optimization> = useMemo(() => {
    return optimizations.filter((row) => rowSelection[row.id]);
  }, [rowSelection, optimizations]);

  return {
    optimizations,
    total,
    selectedRows,
    isPending,
    isPlaceholderData,
    isFetching,
    pageSize: DEFAULT_PAGE_SIZE,
    refetch,
  };
};
