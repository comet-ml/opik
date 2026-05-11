import React from "react";
import { CellContext } from "@tanstack/react-table";
import { ChevronDown, ChevronUp, Database, ListChecks } from "lucide-react";
import get from "lodash/get";
import isFunction from "lodash/isFunction";
import isNumber from "lodash/isNumber";

import { Button } from "@/ui/button";
import { Checkbox } from "@/ui/checkbox";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/shared/ResourceLink/ResourceLink";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { CELL_TEXT_CLASS_MAP } from "@/constants/shared";
import { Explainer, ROW_HEIGHT } from "@/types/shared";
import { DATASET_TYPE, EVALUATION_METHOD } from "@/types/datasets";
import { cn } from "@/lib/utils";

export const ITEM_SOURCE_LABEL = "Item source";

type CustomMeta = {
  nameKey?: string;
  idKey?: string;
  getIsDeleted?: (cellData: unknown) => boolean;
};

const ItemSourceCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const {
    nameKey = "name",
    idKey = "id",
    getIsDeleted,
  } = (custom ?? {}) as CustomMeta;

  const name = get(cellData, nameKey, undefined) as string | undefined;
  const id = get(cellData, idKey, undefined) as string | undefined;
  const evaluationMethod = get(cellData, "evaluation_method", undefined) as
    | EVALUATION_METHOD
    | undefined;
  const isDeleted = isFunction(getIsDeleted)
    ? getIsDeleted(cellData)
    : undefined;

  const isTestSuite = evaluationMethod === EVALUATION_METHOD.TEST_SUITE;
  const Icon = isTestSuite ? ListChecks : Database;
  const resource = isTestSuite
    ? RESOURCE_TYPE.testSuite
    : RESOURCE_TYPE.dataset;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="items-center py-1.5"
    >
      <TooltipWrapper content={name}>
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <span className="flex size-5 shrink-0 items-center justify-center rounded bg-muted text-muted-slate">
            <Icon className="size-3" />
          </span>
          <div className="min-w-0 flex-1">
            {id ? (
              <ResourceLink
                id={id}
                name={name}
                resource={resource}
                isDeleted={isDeleted}
              />
            ) : (
              "-"
            )}
          </div>
        </div>
      </TooltipWrapper>
    </CellWrapper>
  );
};

type GroupCustomMeta = {
  nameKey?: string;
  idKey?: string;
  countAggregationKey?: string;
  explainer?: Explainer;
  datasetTypeMap?: Record<string, DATASET_TYPE>;
  getIsDeleted?: (cellData: unknown) => boolean;
};

const resolveGroupPrefix = (
  evaluationMethod: EVALUATION_METHOD | undefined,
  datasetId: string | undefined,
  datasetTypeMap: Record<string, DATASET_TYPE> | undefined,
): { prefix: string; resource: RESOURCE_TYPE | undefined } => {
  if (evaluationMethod === EVALUATION_METHOD.TEST_SUITE) {
    return { prefix: "Test suite", resource: RESOURCE_TYPE.testSuite };
  }
  if (evaluationMethod === EVALUATION_METHOD.DATASET) {
    return { prefix: "Dataset", resource: RESOURCE_TYPE.dataset };
  }
  if (datasetId && datasetTypeMap) {
    const t = datasetTypeMap[datasetId];
    if (t === DATASET_TYPE.TEST_SUITE) {
      return { prefix: "Test suite", resource: RESOURCE_TYPE.testSuite };
    }
    if (t === DATASET_TYPE.DATASET) {
      return { prefix: "Dataset", resource: RESOURCE_TYPE.dataset };
    }
  }
  return { prefix: ITEM_SOURCE_LABEL, resource: undefined };
};

export const createItemSourceGroupCell = <TData,>(
  checkboxClickHandler: (
    event: React.MouseEvent<HTMLButtonElement>,
    context: CellContext<TData, unknown>,
  ) => void,
) =>
  function ItemSourceGroupCell(context: CellContext<TData, unknown>) {
    const { row, table } = context;
    const { custom } = context.column.columnDef.meta ?? {};
    const cellData = row.original;
    const {
      nameKey = "name",
      idKey = "id",
      countAggregationKey,
      explainer,
      datasetTypeMap,
      getIsDeleted,
    } = (custom ?? {}) as GroupCustomMeta;

    const name = get(cellData, nameKey, undefined) as string | undefined;
    const id = get(cellData, idKey, undefined) as string | undefined;
    const evaluationMethod = get(cellData, "evaluation_method", undefined) as
      | EVALUATION_METHOD
      | undefined;
    const isDeleted = isFunction(getIsDeleted)
      ? getIsDeleted(cellData)
      : undefined;

    const { prefix, resource } = resolveGroupPrefix(
      evaluationMethod,
      id,
      datasetTypeMap,
    );

    const aggregationData = table.options.meta?.aggregationMap?.[row.id];
    const count =
      countAggregationKey && aggregationData
        ? get(aggregationData, countAggregationKey, undefined)
        : undefined;
    const countText = isNumber(count) ? `(${count})` : "";

    const rowHeight = table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
    const textClass = CELL_TEXT_CLASS_MAP[rowHeight];

    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
        className="items-center p-0 pr-2"
        stopClickPropagation
      >
        <div
          className="flex max-w-full items-center overflow-hidden"
          style={{ paddingLeft: `${row.depth * 20}px` }}
        >
          <Checkbox
            variant="muted"
            checked={
              row.getIsAllSubRowsSelected() ||
              (row.getIsSomeSelected() && "indeterminate")
            }
            disabled={!row.getCanSelect()}
            onCheckedChange={(value) => row.toggleSelected(!!value)}
            onClick={(event) => checkboxClickHandler(event, context)}
            aria-label="Select row"
          />
          <Button
            variant="minimal"
            size="sm"
            className={cn("ml-1.5 pr-1", textClass)}
            onClick={(event) => {
              row.toggleExpanded();
              event.stopPropagation();
            }}
          >
            {row.getIsExpanded() ? (
              <ChevronUp className="mr-1 size-4 shrink-0" />
            ) : (
              <ChevronDown className="mr-1 size-4 shrink-0" />
            )}
            <TooltipWrapper content={prefix}>
              <span className="max-w-56 truncate">{prefix}</span>
            </TooltipWrapper>
          </Button>
        </div>
        <div className="flex min-w-0 flex-1 items-center gap-1 overflow-hidden">
          <div className="min-w-0 flex-1">
            {id && resource ? (
              <ResourceLink
                id={id}
                name={name}
                resource={resource}
                isDeleted={isDeleted}
              />
            ) : id ? (
              <span className="comet-body-s block truncate">{name ?? "-"}</span>
            ) : (
              "-"
            )}
          </div>
          <div className="flex shrink-0 items-center">
            {countText}
            {isDeleted && explainer && (
              <ExplainerIcon {...explainer} className="ml-1" />
            )}
          </div>
        </div>
      </CellWrapper>
    );
  };

export default ItemSourceCell;
