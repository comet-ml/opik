import React, { useCallback, useMemo, useState } from "react";
import { useSearch } from "@tanstack/react-router";

import useRulesLogsList from "@/api/automations/useRulesLogsList";
import NoData from "@/components/shared/NoData/NoData";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { EvaluatorRuleLogItem } from "@/types/automations";
import { convertColumnDataToColumn } from "@/lib/table";
import { CellContext } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";

// Reusable cell generator with optional expansion (used only for Message column)
type TextCellOptions = {
  colId: string;
  getValue: (row: EvaluatorRuleLogItem) => string | undefined;
  expandable?: boolean;
};

const AutomationLogsPage = () => {
  const {
    rule_id,
  }: {
    rule_id?: string;
  } = useSearch({ strict: false });

  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >("automation-logs-columns-width", {
    defaultValue: {},
  });

  const makeTextCell = useCallback(
    ({ colId, getValue, expandable = false }: TextCellOptions) => {
      function TextCell(context: CellContext<EvaluatorRuleLogItem, unknown>) {
        const key = `${context.row.index}-${colId}`;
        const isOpen = !!expanded[key];
        const raw = getValue(context.row.original);
        const value = raw ?? "-";
        const firstLine = (value || "").split("\n")[0] || "";

        return (
          <div
            className={`flex h-full items-start px-4 ${
              expandable ? "gap-2" : ""
            }`}
          >
            <span
              className={
                (expandable
                  ? isOpen
                    ? "comet-code whitespace-pre-wrap"
                    : "comet-code truncate"
                  : "comet-code text-foreground-secondary truncate") +
                " flex-1 min-w-0"
              }
            >
              {expandable ? (isOpen ? value : firstLine) : value}
            </span>
            {expandable && (
              <Button
                size="2xs"
                variant="ghost"
                onClick={() =>
                  setExpanded((prev) => ({ ...prev, [key]: !prev[key] }))
                }
                className="shrink-0"
              >
                {isOpen ? "Collapse" : "Expand"}
              </Button>
            )}
          </div>
        );
      }
      TextCell.displayName = `TextCell_${colId}`;
      return TextCell;
    },
    [expanded, setExpanded],
  );

  const { data, isPending } = useRulesLogsList(
    {
      ruleId: rule_id!,
    },
    {
      enabled: Boolean(rule_id),
    },
  );

  const items = useMemo(
    () => (data?.content as EvaluatorRuleLogItem[]) ?? [],
    [data?.content],
  );

  const sortedItems = useMemo(
    () =>
      items.length
        ? [...items].sort(
            (a, b) =>
              new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime(),
          )
        : [],
    [items],
  );

  // Expand/Collapse all control for Message column
  const allExpanded = useMemo(
    () =>
      sortedItems.length > 0 &&
      sortedItems.every((_, index) => expanded[`${index}-message`]),
    [sortedItems, expanded],
  );

  const expandAll = () => {
    setExpanded((prev) => {
      const next = { ...prev } as Record<string, boolean>;
      for (let i = 0; i < sortedItems.length; i += 1) {
        next[`${i}-message`] = true;
      }
      return next;
    });
  };

  const collapseAll = () => setExpanded({});

  // Get unique marker keys for dynamic columns - always call this hook
  const markerKeys = useMemo(() => {
    const keys = new Set<string>();
    items.forEach((item) => {
      if (item.markers) {
        Object.keys(item.markers).forEach((key) => keys.add(key));
      }
    });
    return Array.from(keys).sort();
  }, [items]);

  // Create column definitions (initial sizes; users can resize, persisted)
  const columns = useMemo(() => {
    // Initial sizes (can be overridden by persisted widths)
    const fixedSizes = {
      timestamp: 60,
      level: 30,
      marker: 60,
    };

    const baseColumns: ColumnData<EvaluatorRuleLogItem>[] = [
      {
        id: "timestamp",
        label: "Timestamp",
        type: COLUMN_TYPE.string,
        size: fixedSizes.timestamp,
        cell: makeTextCell({
          colId: "timestamp",
          getValue: (row) => row.timestamp,
        }) as never,
      },
      {
        id: "level",
        label: "Level",
        type: COLUMN_TYPE.string,
        size: fixedSizes.level,
        cell: makeTextCell({
          colId: "level",
          getValue: (row) => row.level,
        }) as never,
      },
    ];

    // Add dynamic marker columns
    const markerColumns: ColumnData<EvaluatorRuleLogItem>[] = markerKeys.map(
      (key) => ({
        id: `marker_${key}`,
        label: key.replace(/_/g, " ").replace(/\b\w/g, (l) => l.toUpperCase()),
        type: COLUMN_TYPE.string,
        size: fixedSizes.marker,
        cell: makeTextCell({
          colId: `marker_${key}`,
          getValue: (row) => row.markers?.[key],
        }) as never,
      }),
    );

    // Add message column (no fixed size → flex grows to fill)
    const messageColumn: ColumnData<EvaluatorRuleLogItem> = {
      id: "message",
      label: "Message",
      type: COLUMN_TYPE.string,
      cell: makeTextCell({
        colId: "message",
        getValue: (row) => row.message,
        expandable: true,
      }) as never,
    };

    const allColumns = [...baseColumns, ...markerColumns, messageColumn];
    return convertColumnDataToColumn<
      EvaluatorRuleLogItem,
      EvaluatorRuleLogItem
    >(allColumns, {});
  }, [markerKeys, makeTextCell]);

  // Enable column resizing and persist widths (as in OnlineEvaluationPage)
  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (!rule_id) {
    return <NoData message="No rule parameters set."></NoData>;
  }

  if (isPending) {
    return <Loader />;
  }

  if (sortedItems.length === 0) {
    return <NoData message="There are no logs for this rule."></NoData>;
  }

  return (
    <div className="mx-6 flex h-full flex-col bg-soft-background">
      <PageBodyScrollContainer className="overflow-x-hidden [scrollbar-gutter:stable]">
        <PageBodyStickyContainer
          className="mb-4 mt-6 flex items-center justify-between"
          direction="horizontal"
        >
          <h1 className="comet-title-l truncate break-words">Logs</h1>
          <div className="flex items-center gap-2">
            {allExpanded ? (
              <Button variant="secondary" size="sm" onClick={collapseAll}>
                Collapse all
              </Button>
            ) : (
              <Button variant="secondary" size="sm" onClick={expandAll}>
                Expand all
              </Button>
            )}
          </div>
        </PageBodyStickyContainer>
        <DataTable
          columns={columns}
          data={sortedItems}
          noData={<DataTableNoData title="There are no logs for this rule." />}
          TableWrapper={PageBodyStickyTableWrapper}
          stickyHeader
          resizeConfig={resizeConfig}
        />
      </PageBodyScrollContainer>
    </div>
  );
};

export default AutomationLogsPage;
