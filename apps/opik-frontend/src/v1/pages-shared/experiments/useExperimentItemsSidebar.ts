import { useCallback, useMemo } from "react";
import { ArrayParam, StringParam, useQueryParam } from "use-query-params";

import { ExperimentsCompare } from "@/types/datasets";

const useExperimentItemsSidebar = (rows: ExperimentsCompare[]) => {
  const [activeRowId = "", setActiveRowId] = useQueryParam("row", StringParam, {
    updateType: "replaceIn",
  });

  const [, setExpandedCommentSections] = useQueryParam(
    "expandedCommentSections",
    ArrayParam,
    { updateType: "replaceIn" },
  );

  const [traceId = "", setTraceId] = useQueryParam("trace", StringParam, {
    updateType: "replaceIn",
  });

  const [spanId = "", setSpanId] = useQueryParam("span", StringParam, {
    updateType: "replaceIn",
  });

  const handleRowClick = useCallback(
    (row: ExperimentsCompare) => {
      setActiveRowId((state) => (row.id === state ? "" : row.id));
    },
    [setActiveRowId],
  );

  const rowIndex = rows.findIndex((row) => activeRowId === row.id);

  const hasNext = rowIndex >= 0 && rowIndex < rows.length - 1;
  const hasPrevious = rowIndex > 0;

  const handleRowChange = useCallback(
    (shift: number) => {
      setActiveRowId(rows[rowIndex + shift]?.id ?? "");
    },
    [rowIndex, rows, setActiveRowId],
  );

  const handleClose = useCallback(() => {
    setExpandedCommentSections(null);
    setActiveRowId("");
  }, [setActiveRowId, setExpandedCommentSections]);

  const activeRow = useMemo(
    () => rows.find((row) => activeRowId === row.id),
    [activeRowId, rows],
  );

  return {
    activeRowId,
    setActiveRowId,
    activeRow,
    traceId,
    setTraceId,
    spanId,
    setSpanId,
    handleRowClick,
    handleRowChange,
    handleClose,
    hasNext,
    hasPrevious,
    setExpandedCommentSections,
  };
};

export default useExperimentItemsSidebar;
