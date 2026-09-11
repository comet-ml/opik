import { Span, Trace, TraceFeedbackScore } from "@/types/traces";
import get from "lodash/get";
import isEmpty from "lodash/isEmpty";
import isString from "lodash/isString";
import isNumber from "lodash/isNumber";
import isArray from "lodash/isArray";
import isObject from "lodash/isObject";
import isNil from "lodash/isNil";
import toString from "lodash/toString";
import toLower from "lodash/toLower";
import trim from "lodash/trim";
import some from "lodash/some";
import every from "lodash/every";
import startsWith from "lodash/startsWith";
import endsWith from "lodash/endsWith";
import includes from "lodash/includes";
import isUndefined from "lodash/isUndefined";
import { JSONPath } from "jsonpath-plus";

import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { Filter, FilterOperator, Filters } from "@/types/filters";
import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_CUSTOM_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";

export const SPAN_TYPE_FILTER_COLUMN: ColumnData<Span> = {
  id: "type",
  label: "Span type",
  type: COLUMN_TYPE.category,
};

export const TREE_FILTER_COLUMNS: ColumnData<Span>[] = [
  SPAN_TYPE_FILTER_COLUMN,
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "input",
    label: "Input",
    type: COLUMN_TYPE.string,
  },
  {
    id: "output",
    label: "Output",
    type: COLUMN_TYPE.string,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "usage.total_tokens",
    label: "Total tokens",
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.prompt_tokens",
    label: "Total input tokens",
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.completion_tokens",
    label: "Total output tokens",
    type: COLUMN_TYPE.number,
  },
  {
    id: "total_estimated_cost",
    label: "Estimated cost",
    type: COLUMN_TYPE.cost,
  },
  {
    id: "error_info",
    label: "Errors",
    type: COLUMN_TYPE.errors,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
  },
];

export enum FILTER_FIELDS {
  TYPE = "type",
  NAME = "name",
  INPUT = "input",
  OUTPUT = "output",
  METADATA = "metadata",
  TOKEN_USAGE = "usage",
  ERROR_INFO = "error_info",
}

const getData = (field: FILTER_FIELDS, data: Trace | Span): string => {
  if (!data) return "";

  switch (field) {
    case FILTER_FIELDS.TYPE:
      return toLower(get(data, field, TRACE_TYPE_FOR_TREE));
    case FILTER_FIELDS.NAME:
      return toLower(get(data, field, ""));
    case FILTER_FIELDS.INPUT:
    case FILTER_FIELDS.OUTPUT:
    case FILTER_FIELDS.TOKEN_USAGE:
    case FILTER_FIELDS.ERROR_INFO:
    case FILTER_FIELDS.METADATA: {
      const value = get(data, field, false);
      return value ? toLower(JSON.stringify(value)) : "";
    }
    default:
      return "";
  }
};

const filterField = (
  fieldValue: string,
  operator: FilterOperator,
  value: string,
): boolean => {
  if (fieldValue) {
    switch (operator) {
      case "=":
        return fieldValue === value;
      case "contains":
        return includes(fieldValue, value);
      case "not_contains":
        return !includes(fieldValue, value);
      case "starts_with":
        return startsWith(fieldValue, value);
      case "ends_with":
        return endsWith(fieldValue, value);
      default:
        return false;
    }
  }

  return false;
};

const search = (searchValue: string, data: Trace | Span): boolean =>
  filterField(getData(FILTER_FIELDS.TYPE, data), "contains", searchValue) ||
  filterField(getData(FILTER_FIELDS.NAME, data), "contains", searchValue) ||
  filterField(getData(FILTER_FIELDS.INPUT, data), "contains", searchValue) ||
  filterField(getData(FILTER_FIELDS.OUTPUT, data), "contains", searchValue) ||
  filterField(getData(FILTER_FIELDS.METADATA, data), "contains", searchValue) ||
  filterField(
    getData(FILTER_FIELDS.TOKEN_USAGE, data),
    "contains",
    searchValue,
  ) ||
  filterField(getData(FILTER_FIELDS.ERROR_INFO, data), "contains", searchValue);

const getFieldValue = (
  fieldId: string,
  data: Trace | Span,
  key?: string,
  type?: COLUMN_TYPE,
): unknown => {
  if (!data) return undefined;
  const fieldValue = get(data, fieldId, undefined);

  if (type === COLUMN_TYPE.numberDictionary) {
    if (isArray(fieldValue)) {
      return (
        (fieldValue as Array<TraceFeedbackScore>).find((s) => s.name === key)
          ?.value ?? undefined
      );
    } else {
      return undefined;
    }
  }

  if (key && !isUndefined(fieldValue)) {
    try {
      // Ensure the path starts with $ for JSONPath
      const jsonPath = key.startsWith("$") ? key : `$.${key}`;
      const result = JSONPath({ path: jsonPath, json: fieldValue });

      if (result.length === 0) return undefined;
      if (result.length === 1) return result[0];
      return result;
    } catch (error) {
      console.warn(`Invalid JSONPath expression: ${key}`, error);
      return undefined;
    }
  }

  return fieldValue;
};

const isValueEmpty = (value: unknown): boolean => {
  if (isNil(value)) return true;
  if (isString(value) && isEmpty(trim(value))) return true;
  if (isArray(value) && isEmpty(value)) return true;
  return !!(isObject(value) && !isArray(value) && isEmpty(value));
};

const normalizeValueForFiltering = (value: unknown): string => {
  if (isArray(value)) {
    return toLower(value.map((item) => toString(item)).join(" "));
  }
  if (isObject(value) && !isNil(value)) {
    return toLower(JSON.stringify(value));
  }
  return toLower(toString(value));
};

const applyStringOperator = (
  fieldValue: unknown,
  operator: FilterOperator,
  filterValue: unknown,
): boolean =>
  filterField(
    normalizeValueForFiltering(fieldValue),
    operator,
    toLower(toString(filterValue)),
  );

const applyNumberOperator = (
  fieldValue: unknown,
  operator: FilterOperator,
  filterValue: unknown,
): boolean => {
  const fieldNum = Number(fieldValue);
  const filterNum = Number(filterValue);

  if (!isNumber(fieldNum) || !isNumber(filterNum)) return false;

  switch (operator) {
    case "=":
      return fieldNum === filterNum;
    case ">":
      return fieldNum > filterNum;
    case ">=":
      return fieldNum >= filterNum;
    case "<":
      return fieldNum < filterNum;
    case "<=":
      return fieldNum <= filterNum;
    default:
      return false;
  }
};

const applyListOperator = (
  fieldValue: unknown,
  operator: FilterOperator,
  filterValue: unknown,
): boolean => {
  const fieldArray = isArray(fieldValue) ? fieldValue : [fieldValue];
  const filterStr = toLower(toString(filterValue));

  switch (operator) {
    case "contains":
      return some(fieldArray, (item) =>
        includes(normalizeValueForFiltering(item), filterStr),
      );
    default:
      return false;
  }
};

const applyDictionaryOperator = (
  fieldValue: unknown,
  operator: FilterOperator,
  filterValue: unknown,
): boolean => {
  const fieldDict = normalizeValueForFiltering(fieldValue);
  const filterDict = toLower(toString(filterValue));

  switch (operator) {
    case "=":
      return fieldDict === filterDict;
    case "contains":
      return includes(fieldDict, filterDict);
    case ">": {
      const fieldNum = Number(fieldValue);
      const filterNum = Number(filterValue);
      return isNumber(fieldNum) && isNumber(filterNum) && fieldNum > filterNum;
    }
    case "<": {
      const fieldNum = Number(fieldValue);
      const filterNum = Number(filterValue);
      return isNumber(fieldNum) && isNumber(filterNum) && fieldNum < filterNum;
    }
    default:
      return false;
  }
};

const applyTimeOperator = (
  fieldValue: unknown,
  operator: FilterOperator,
  filterValue: unknown,
): boolean => {
  const fieldTime = new Date(toString(fieldValue)).getTime();
  const filterTime = new Date(toString(filterValue)).getTime();

  if (!isNumber(fieldTime) || !isNumber(filterTime)) return false;

  switch (operator) {
    case "=":
      return fieldTime === filterTime;
    case ">":
      return fieldTime > filterTime;
    case ">=":
      return fieldTime >= filterTime;
    case "<":
      return fieldTime < filterTime;
    case "<=":
      return fieldTime <= filterTime;
    default:
      return false;
  }
};

const applyCategoryOperator = (
  fieldValue: unknown,
  operator: FilterOperator,
  filterValue: unknown,
): boolean => {
  const fieldStatus = normalizeValueForFiltering(fieldValue);
  const filterStatus = toLower(toString(filterValue));

  switch (operator) {
    case "=":
      return fieldStatus === filterStatus;
    default:
      return false;
  }
};

const applyOperator = (
  fieldValue: unknown,
  operator: FilterOperator,
  filterValue: unknown,
  columnType: COLUMN_TYPE,
): boolean => {
  // Handle is_empty and is_not_empty operators first
  if (operator === "is_empty") {
    return isValueEmpty(fieldValue);
  }
  if (operator === "is_not_empty") {
    return !isValueEmpty(fieldValue);
  }

  // If field value is empty and we're not checking for emptiness, return false
  if (isValueEmpty(fieldValue)) {
    return false;
  }

  switch (columnType) {
    case COLUMN_TYPE.string:
      return applyStringOperator(fieldValue, operator, filterValue);

    case COLUMN_TYPE.number:
    case COLUMN_TYPE.duration:
    case COLUMN_TYPE.cost:
    case COLUMN_TYPE.numberDictionary:
      return applyNumberOperator(fieldValue, operator, filterValue);

    case COLUMN_TYPE.list:
      return applyListOperator(fieldValue, operator, filterValue);

    case COLUMN_TYPE.dictionary:
      return applyDictionaryOperator(fieldValue, operator, filterValue);

    case COLUMN_TYPE.time:
      return applyTimeOperator(fieldValue, operator, filterValue);

    case COLUMN_TYPE.category:
      return applyCategoryOperator(fieldValue, operator, filterValue);

    case COLUMN_TYPE.errors:
      // For errors, we only support is_empty and is_not_empty which are handled above
      return false;

    default:
      return false;
  }
};

const processFilter = (filterItem: Filter): Filter => {
  if (filterItem.field === COLUMN_CUSTOM_ID && filterItem.key) {
    const { field, key: originalKey, type: originalType } = filterItem;
    let key = originalKey;
    let type = originalType;
    let processedField: string = field;
    const prefixes = [
      { fieldName: "input" as const, prefix: "input." },
      { fieldName: "output" as const, prefix: "output." },
    ];

    for (const { fieldName, prefix } of prefixes) {
      if (key.startsWith(prefix)) {
        processedField = fieldName;
        key = key.substring(prefix.length);
        type = COLUMN_TYPE.dictionary;
        break;
      }
    }
    return { ...filterItem, field: processedField, key, type };
  }

  return filterItem;
};

const filter = (filters: Filters, data: Trace | Span): boolean => {
  if (isEmpty(filters)) {
    return true;
  }

  return every(filters, (filterItem) => {
    const { field, key, type, operator, value } = processFilter(filterItem);

    if (!field || !operator) return true; // Skip invalid filters

    const fieldValue = getFieldValue(field, data, key, type as COLUMN_TYPE);
    return applyOperator(fieldValue, operator, value, type as COLUMN_TYPE);
  });
};

export const filterFunction = (
  data: Trace | Span,
  filters?: Filters,
  searchValue?: string,
): boolean => {
  const hasSearched = searchValue ? search(toLower(searchValue), data) : true;
  const hasFiltered = filters ? filter(filters, data) : true;

  return hasSearched && hasFiltered;
};

export const constructDataMapAndSearchIds = (
  trace: Trace,
  traceSpans: Span[],
  predicate: (data: Trace | Span) => boolean,
): [Map<string, Span | Trace>, Set<string>] => {
  const dataMap = new Map<string, Span | Trace>([[trace.id, trace]]);
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
  dataMap: Map<string, Span | Trace>,
): Set<string> => {
  const parentIds = new Set<string>();

  const ensureParent = (id: string): void => {
    if (!parentIds.has(id)) {
      parentIds.add(id);
      const data = dataMap.get(id);
      const parentId = get(data, "parent_span_id");
      if (parentId) {
        ensureParent(parentId);
      }
    }
  };

  searchIds.forEach((id) => {
    ensureParent(id);
  });

  return parentIds;
};
