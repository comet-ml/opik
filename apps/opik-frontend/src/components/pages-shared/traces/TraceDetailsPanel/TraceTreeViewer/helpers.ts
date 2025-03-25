import { Span, Trace } from "@/types/traces";
import get from "lodash/get";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { COLUMN_TYPE } from "@/types/shared";
import { FilterOperator } from "@/types/filters";

export enum FILTER_FIELDS {
  TYPE = "type",
  NAME = "name",
  INPUT = "input",
  OUTPUT = "output",
  METADATA = "metadata",
}

export const FILTER_FIELDS_LIST = [
  {
    id: FILTER_FIELDS.TYPE,
    label: "Type",
    type: COLUMN_TYPE.string,
  },
  {
    id: FILTER_FIELDS.NAME,
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: FILTER_FIELDS.INPUT,
    label: "Input",
    type: COLUMN_TYPE.string,
  },
  {
    id: FILTER_FIELDS.OUTPUT,
    label: "Output",
    type: COLUMN_TYPE.string,
  },
  {
    id: FILTER_FIELDS.METADATA,
    label: "Metadata",
    type: COLUMN_TYPE.string,
  },
];

export type TreeItemWidthObject = {
  id: string;
  name: string;
  parentId?: string;
  children: TreeItemWidthObject[];
  level?: number;
};

export const getSpansWithLevel = (
  item: TreeItemWidthObject,
  accumulator: TreeItemWidthObject[] = [],
  level = 0,
) => {
  accumulator.push({
    ...item,
    level,
  });

  if (item.children) {
    item.children.forEach((i) => getSpansWithLevel(i, accumulator, level + 1));
  }
  return accumulator;
};

const getData = (field: FILTER_FIELDS, data: Trace | Span) => {
  if (!data) return "";

  switch (field) {
    case FILTER_FIELDS.TYPE:
      return get(data, field, TRACE_TYPE_FOR_TREE).toLowerCase();
    case FILTER_FIELDS.NAME:
      return get(data, field, "").toLowerCase();
    case FILTER_FIELDS.INPUT:
    case FILTER_FIELDS.OUTPUT:
    case FILTER_FIELDS.METADATA:
      return get(data, field, false)
        ? JSON.stringify(get(data, field, "")).toLowerCase()
        : "";
  }
};

export const filterField = (
  field: FILTER_FIELDS,
  data: Trace | Span,
  operator: FilterOperator,
  value: string,
) => {
  const fieldValue = getData(field, data);

  if (fieldValue) {
    switch (operator) {
      case "=":
        return fieldValue === value;
      case "contains":
        return fieldValue.includes(value);
      case "not_contains":
        return !fieldValue.includes(value);
      case "starts_with":
        return fieldValue.startsWith(value);
      case "ends_with":
        return fieldValue.endsWith(value);
    }
  }

  return false;
};

export const searchFunction = (searchValue: string, data: Trace | Span) =>
  filterField(FILTER_FIELDS.TYPE, data, "contains", searchValue) ||
  filterField(FILTER_FIELDS.NAME, data, "contains", searchValue) ||
  filterField(FILTER_FIELDS.INPUT, data, "contains", searchValue) ||
  filterField(FILTER_FIELDS.OUTPUT, data, "contains", searchValue) ||
  filterField(FILTER_FIELDS.METADATA, data, "contains", searchValue);

export const constructDataMapAndSearchIds = (
  trace: Trace,
  traceSpans: Span[],
  predicate: (data: Trace | Span) => boolean,
): [Map<string, Span>, Set<string>] => {
  const dataMap = new Map<string, Span>();
  const searchIds = new Set<string>();

  if (predicate(trace)) {
    searchIds.add(trace.id);
  }

  traceSpans.forEach((s) => {
    dataMap.set(s.id, s);
    if (predicate(s)) {
      searchIds.add(s.id);
    }
  });

  return [dataMap, searchIds];
};

export const addAllParentIds = (
  searchIds: Set<string>,
  dataMap: Map<string, Span>,
): Set<string> => {
  const parentIds = new Set<string>();

  const ensureParent = (id: string) => {
    if (!parentIds.has(id)) {
      parentIds.add(id);
      const parentId = dataMap.get(id)?.parent_span_id;
      if (parentId) {
        ensureParent(parentId);
      }
    }
  };

  for (const id of searchIds) {
    ensureParent(id);
  }

  return parentIds;
};
