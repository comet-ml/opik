import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import * as OpikApi from "@/rest_api/api";
import { serialization } from "@/rest_api";
import { parseNdjsonStreamToArray } from "@/utils/stream";
import { OpikQueryLanguage } from "@/query";

/**
 * Searches traces with filters and returns parsed results
 */
export async function searchTracesWithFilters(
  apiClient: OpikApiClientTemp,
  projectName: string,
  filters: OpikApi.TraceFilterPublic[] | null,
  maxResults: number,
  truncate: boolean
): Promise<OpikApi.TracePublic[]> {
  const streamResponse = await apiClient.traces.searchTraces({
    projectName,
    filters: filters ?? undefined,
    limit: maxResults,
    truncate,
  });

  const traces = await parseNdjsonStreamToArray<OpikApi.TracePublic>(
    streamResponse,
    serialization.TracePublic,
    maxResults
  );

  return traces;
}

/**
 * Generic polling function that repeatedly calls a search function until conditions are met
 */
export async function searchAndWaitForDone<T>(
  searchFn: () => Promise<T[]>,
  waitForAtLeast: number,
  waitForTimeout: number,
  sleepTime: number
): Promise<T[]> {
  const startTime = Date.now();
  let result: T[] = [];

  while (true) {
    // Execute search
    result = await searchFn();

    // Check if condition is met
    if (result.length >= waitForAtLeast) {
      return result;
    }

    // Check if timeout exceeded
    const elapsedTime = Date.now() - startTime;
    if (elapsedTime >= waitForTimeout) {
      // Return best attempt results
      return result;
    }

    // Sleep before next attempt
    await new Promise((resolve) => setTimeout(resolve, sleepTime));
  }
}

/**
 * Searches threads with filters and returns parsed results
 */
export async function searchThreadsWithFilters(
  apiClient: OpikApiClientTemp,
  projectName: string,
  filters: OpikApi.TraceThreadFilter[] | null,
  maxResults: number,
  truncate: boolean
): Promise<OpikApi.TraceThread[]> {
  const streamResponse = await apiClient.traces.searchTraceThreads({
    projectName,
    filters: filters ?? undefined,
    limit: maxResults,
    truncate,
  });

  const threads = await parseNdjsonStreamToArray<OpikApi.TraceThread>(
    streamResponse,
    serialization.TraceThread,
    maxResults
  );

  return threads;
}

/**
 * Generic filter parsing function that works for both traces and threads
 */
function parseFilterStringGeneric<TFilter, TOperator>(
  filterString: string | undefined,
  operatorCast: (op: string) => TOperator,
  oqlFactory: (filterString: string) => OpikQueryLanguage
): TFilter[] | null {
  if (!filterString) {
    return null;
  }

  const oql = oqlFactory(filterString);
  const filterExpressions = oql.getFilterExpressions();

  if (!filterExpressions) {
    return null;
  }

  return filterExpressions.map((expr) => {
    const filter: {
      field: string;
      operator: TOperator;
      value: string | null;
      key?: string;
    } = {
      field: expr.field,
      operator: operatorCast(expr.operator),
      value: expr.value,
    };

    if (expr.key) {
      filter.key = expr.key;
    }

    return filter as TFilter;
  });
}

/**
 * Parses a filter string using OpikQueryLanguage and converts to TraceFilterPublic format
 */
export function parseFilterString(
  filterString?: string
): OpikApi.TraceFilterPublic[] | null {
  return parseFilterStringGeneric<
    OpikApi.TraceFilterPublic,
    OpikApi.TraceFilterPublicOperator
  >(
    filterString,
    (op) => op as OpikApi.TraceFilterPublicOperator,
    OpikQueryLanguage.forTraces
  );
}

/**
 * Parses a filter string using OpikQueryLanguage and converts to TraceThreadFilter format
 */
export function parseThreadFilterString(
  filterString?: string
): OpikApi.TraceThreadFilter[] | null {
  return parseFilterStringGeneric<
    OpikApi.TraceThreadFilter,
    OpikApi.TraceThreadFilterOperator
  >(
    filterString,
    (op) => op as OpikApi.TraceThreadFilterOperator,
    OpikQueryLanguage.forThreads
  );
}

/**
 * Searches spans with filters and returns parsed results
 */
export async function searchSpansWithFilters(
  apiClient: OpikApiClientTemp,
  projectName: string,
  filters: OpikApi.SpanFilterPublic[] | null,
  maxResults: number,
  truncate: boolean
): Promise<OpikApi.SpanPublic[]> {
  const streamResponse = await apiClient.spans.searchSpans({
    projectName,
    filters: filters ?? undefined,
    limit: maxResults,
    truncate,
  });

  const spans = await parseNdjsonStreamToArray<OpikApi.SpanPublic>(
    streamResponse,
    serialization.SpanPublic,
    maxResults
  );

  return spans;
}

/**
 * Parses a filter string using OpikQueryLanguage and converts to SpanFilterPublic format
 */
export function parseSpanFilterString(
  filterString?: string
): OpikApi.SpanFilterPublic[] | null {
  return parseFilterStringGeneric<
    OpikApi.SpanFilterPublic,
    OpikApi.SpanFilterPublicOperator
  >(
    filterString,
    (op) => op as OpikApi.SpanFilterPublicOperator,
    OpikQueryLanguage.forSpans
  );
}
