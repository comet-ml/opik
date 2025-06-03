import uniqid from "uniqid";
import flatten from "lodash/flatten";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE, DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { TRACE_VISIBILITY_MODE } from "@/types/traces";
import {
  makeEndOfMinute,
  makeStartOfMinute,
  secondsToMilliseconds,
} from "@/lib/date";

export const isFilterValid = (filter: Filter) => {
  const hasValue =
    filter.value !== "" ||
    filter.operator === "is_empty" ||
    filter.operator === "is_not_empty";

  const hasKey =
    filter.type === COLUMN_TYPE.dictionary ||
    filter.type === COLUMN_TYPE.numberDictionary
      ? filter.key !== ""
      : true;

  return hasValue && hasKey;
};

export const createEmptyFilter = () => {
  return {
    id: uniqid(),
    field: "",
    type: "",
    operator: "",
    key: "",
    value: "",
  } as Filter;
};

export const generateSearchByIDFilters = (search?: string) => {
  if (!search) return undefined;

  return [
    {
      id: uniqid(),
      field: "id",
      type: COLUMN_TYPE.string,
      operator: "contains",
      key: "",
      value: search,
    },
  ] as Filter[];
};

export const generateVisibilityFilters = () => {
  return [
    {
      id: uniqid(),
      field: "visibility_mode",
      type: COLUMN_TYPE.string,
      operator: "=",
      key: "",
      value: TRACE_VISIBILITY_MODE.default,
    },
  ] as Filter[];
};

const processTimeFilter: (filter: Filter) => Filter | Filter[] = (filter) => {
  switch (filter.operator) {
    case "=":
      return [
        {
          ...filter,
          operator: ">",
          value: makeStartOfMinute(filter.value as string),
        },
        {
          ...filter,
          operator: "<",
          value: makeEndOfMinute(filter.value as string),
        },
      ];
    case ">":
    case "<=":
      return [
        {
          ...filter,
          value: makeEndOfMinute(filter.value as string),
        },
      ];
    case "<":
    case ">=":
      return [
        {
          ...filter,
          value: makeStartOfMinute(filter.value as string),
        },
      ];
    default:
      return filter;
  }
};

const processDurationFilter: (filter: Filter) => Filter = (filter) => ({
  ...filter,
  value: secondsToMilliseconds(Number(filter.value)).toString(),
});

const processFiltersArray = (filters: Filter[]) => {
  return flatten(
    filters.map((filter) => {
      switch (filter.type) {
        case COLUMN_TYPE.time:
          return processTimeFilter(filter);
        case COLUMN_TYPE.duration:
          return processDurationFilter(filter);
        default:
          return filter;
      }
    }),
  );
};

export const processFilters = (
  filters?: Filter[],
  additionalFilters?: Filter[],
) => {
  const retVal: {
    filters?: string;
  } = {};
  const processedFilters: Filter[] = [];

  if (filters && filters.length > 0) {
    processFiltersArray(filters).forEach((f) => processedFilters.push(f));
  }

  if (additionalFilters && additionalFilters.length > 0) {
    processFiltersArray(additionalFilters).forEach((f) =>
      processedFilters.push(f),
    );
  }

  if (processedFilters.length > 0) {
    retVal.filters = JSON.stringify(processedFilters);
  }

  return retVal;
};

export const mapDynamicColumnTypesToColumnType = (
  types: DYNAMIC_COLUMN_TYPE[] = [],
) => {
  if (
    types.includes(DYNAMIC_COLUMN_TYPE.object) ||
    types.includes(DYNAMIC_COLUMN_TYPE.array)
  ) {
    return COLUMN_TYPE.dictionary;
  }

  if (
    types.includes(DYNAMIC_COLUMN_TYPE.string) ||
    types.includes(DYNAMIC_COLUMN_TYPE.boolean) ||
    types.includes(DYNAMIC_COLUMN_TYPE.null)
  ) {
    return COLUMN_TYPE.string;
  }

  if (types.includes(DYNAMIC_COLUMN_TYPE.number)) {
    return COLUMN_TYPE.number;
  }

  return COLUMN_TYPE.string;
};
