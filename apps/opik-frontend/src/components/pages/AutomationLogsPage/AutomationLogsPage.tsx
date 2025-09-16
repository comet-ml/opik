import React, { useMemo, useState } from "react";
import { useSearch } from "@tanstack/react-router";
import { flatMap, get, uniq } from "lodash";
import md5 from "md5";
import { FoldVertical, RotateCw, UnfoldVertical } from "lucide-react";

import useRulesLogsList from "@/api/automations/useRulesLogsList";
import NoData from "@/components/shared/NoData/NoData";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import ExpandableTextCell from "@/components/shared/DataTableCells/ExpandableTextCell";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  EvaluatorRuleLogItem,
  EvaluatorRuleLogItemWithId,
} from "@/types/automations";
import { convertColumnDataToColumn } from "@/lib/table";
import useLocalStorageState from "use-local-storage-state";
import { formatDate } from "@/lib/date";

const generateEvaluatorRuleLogItemKey = (
  item: EvaluatorRuleLogItem,
): string => {
  const messageHash = md5(item.message);
  return `${item.timestamp}-${item.level}-${messageHash}`;
};

const BASE_COLUMNS: ColumnData<EvaluatorRuleLogItemWithId>[] = [
  {
    id: "timestamp",
    label: "Timestamp",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => formatDate(row.timestamp),
    size: 180,
  },
  {
    id: "level",
    label: "Level",
    type: COLUMN_TYPE.string,
    size: 80,
  },
];

const COLUMNS_WIDTH_KEY = "automation-logs-columns-width";

const AutomationLogsPage = () => {
  const {
    rule_id,
  }: {
    rule_id?: string;
  } = useSearch({ strict: false });

  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const { data, isPending, refetch } = useRulesLogsList(
    {
      ruleId: rule_id!,
    },
    {
      enabled: Boolean(rule_id),
    },
  );

  const { rows, markerKeys } = useMemo(() => {
    const rawRows =
      data?.content.sort((a, b) => b.timestamp.localeCompare(a.timestamp)) ??
      [];

    const sortedRowsWithId: EvaluatorRuleLogItemWithId[] = rawRows.map(
      (item) => ({
        ...item,
        id: generateEvaluatorRuleLogItemKey(item),
      }),
    );

    const allMarkerKeys = uniq(
      flatMap(sortedRowsWithId, (item) =>
        item.markers ? Object.keys(item.markers) : [],
      ),
    ).sort();

    return {
      rows: sortedRowsWithId,
      markerKeys: allMarkerKeys,
    };
  }, [data?.content]);

  const allExpanded = useMemo(
    () => rows.length > 0 && rows.every((row) => expanded[row.id]),
    [rows, expanded],
  );

  const toggleExpandAll = () => {
    if (allExpanded) {
      setExpanded({});
    } else {
      setExpanded((prev) => {
        const next = { ...prev } as Record<string, boolean>;
        rows.forEach((row) => {
          next[row.id] = true;
        });
        return next;
      });
    }
  };

  const columns = useMemo(() => {
    const markerColumns: ColumnData<EvaluatorRuleLogItemWithId>[] =
      markerKeys.map((key) => ({
        id: `marker_${key}`,
        label: key.replace(/_/g, " ").replace(/\b\w/g, (l) => l.toUpperCase()),
        type: COLUMN_TYPE.string,
        accessorFn: (row) => get(row, ["markers", key], ""),
      }));

    const messageColumn: ColumnData<EvaluatorRuleLogItemWithId> = {
      id: "message",
      label: "Message",
      type: COLUMN_TYPE.string,
      cell: ExpandableTextCell as never,
      size: 400,
      customMeta: {
        expandedState: expanded,
        setExpandedState: setExpanded,
        getShortValue: (value: string) => (value || "").split("\n")[0] || "",
        getIsExpandable: (value: string) =>
          (value || "").split("\n").length > 1,
      },
    };

    const allColumns = [...BASE_COLUMNS, ...markerColumns, messageColumn];
    return convertColumnDataToColumn<
      EvaluatorRuleLogItemWithId,
      EvaluatorRuleLogItemWithId
    >(allColumns, {});
  }, [markerKeys, expanded, setExpanded]);

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

  if (rows.length === 0) {
    return <NoData message="There are no logs for this rule."></NoData>;
  }

  return (
    <div className="mx-6 flex h-full flex-col bg-soft-background">
      <PageBodyScrollContainer>
        <PageBodyStickyContainer
          className="flex items-center justify-between pb-4 pt-6"
          direction="bidirectional"
        >
          <h1 className="comet-title-l truncate break-words">Logs</h1>
          <div className="flex items-center gap-2">
            <TooltipWrapper content="Refresh logs list">
              <Button
                variant="outline"
                size="icon-sm"
                className="shrink-0"
                onClick={() => {
                  refetch();
                }}
              >
                <RotateCw />
              </Button>
            </TooltipWrapper>
            <TooltipWrapper
              content={allExpanded ? "Collapse all" : "Expand all"}
            >
              <Button
                onClick={toggleExpandAll}
                variant="outline"
                size="icon-sm"
              >
                {allExpanded ? <FoldVertical /> : <UnfoldVertical />}
              </Button>
            </TooltipWrapper>
          </div>
        </PageBodyStickyContainer>
        <DataTable
          columns={columns}
          data={rows}
          noData={<DataTableNoData title="There are no logs for this rule." />}
          TableWrapper={PageBodyStickyTableWrapper}
          getRowId={(row) => row.id}
          stickyHeader
          resizeConfig={resizeConfig}
        />
      </PageBodyScrollContainer>
    </div>
  );
};

export default AutomationLogsPage;
