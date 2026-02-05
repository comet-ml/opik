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

  const hasError = filter.error && filter.error.length > 0;

  return hasValue && hasKey && !hasError;
};

export const createFilter = (filter?: Partial<Filter>) => {
  return {
    id: uniqid(),
    field: "",
    type: "",
    operator: "",
    key: "",
    value: "",
    ...filter,
  } as Filter;
};

export const generateSearchByFieldFilters = (
  field: string,
  search?: string,
) => {
  if (!search) return [];

  return [
    {
      id: uniqid(),
      field,
      type: COLUMN_TYPE.string,
      operator: "contains",
      key: "",
      value: search,
    },
  ] as Filter[];
};

export const generateSearchByIDFilters = (search?: string) => {
  return generateSearchByFieldFilters("id", search);
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

export const generatePromptFilters = (promptId?: string) => {
  if (!promptId) return undefined;

  return [
    {
      id: uniqid(),
      field: "prompt_ids",
      type: COLUMN_TYPE.string,
      operator: "contains",
      key: "",
      value: promptId,
    },
  ] as Filter[];
};

export const generateProjectFilters = (projectId?: string) => {
  if (!projectId) return undefined;

  return [
    {
      id: uniqid(),
      field: "project_id",
      type: COLUMN_TYPE.string,
      operator: "=",
      key: "",
      value: projectId,
    },
  ] as Filter[];
};

export const generateExperimentIdFilter = (experimentId?: string) => {
  if (!experimentId) return [];

  return [
    createFilter({
      field: "experiment_id",
      type: COLUMN_TYPE.string,
      operator: "=",
      value: experimentId,
    }),
  ];
};

export const generateAnnotationQueueIdFilter = (annotationQueueId?: string) => {
  if (!annotationQueueId) return [];

  return [
    createFilter({
      id: `annotation-queue-filter-${annotationQueueId}`,
      field: "annotation_queue_ids",
      type: COLUMN_TYPE.list,
      operator: "contains",
      value: annotationQueueId,
    }),
  ];
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

export const processFiltersArray = (filters: Filter[]) => {
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
    // Only send fields that the backend expects: field, type, operator, value, and key (when present)
    const backendFilters = processedFilters.map(
      ({ field, operator, value, key, type }) => {
        // Include key for dictionary types or when explicitly set (e.g., for MAP field filtering)
        const isDictionary =
          type === COLUMN_TYPE.dictionary ||
          type === COLUMN_TYPE.numberDictionary;
        const hasKey = key !== undefined && key !== "";
        return isDictionary || hasKey
          ? { field, type, operator, value, key }
          : { field, type, operator, value };
      },
    );
    retVal.filters = JSON.stringify(backendFilters);
  }

  return retVal;
};

export const mapDynamicColumnTypesToColumnType = (
  types: DYNAMIC_COLUMN_TYPE[] = [],
) => {
  // Handle array first - map to LIST for simple element filtering (no key required)
  if (types.includes(DYNAMIC_COLUMN_TYPE.array)) {
    return COLUMN_TYPE.list;
  }

  // Only objects require dictionary type (with key input)
  if (types.includes(DYNAMIC_COLUMN_TYPE.object)) {
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
