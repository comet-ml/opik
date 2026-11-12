import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import * as OpikApi from "@/rest_api/api";
import { serialization } from "@/rest_api";
import type * as core from "@/rest_api/core";
import { parseNdjsonStreamToArray } from "@/utils/stream";
import { OpikQueryLanguage } from "@/query";

const MAX_SEARCH_BATCH_SIZE = 2000;

async function readPage<T>(
  stream: ReadableStream<Uint8Array>,
  serializer: core.serialization.Schema<unknown, T>,
  cursorField: string,
  limit: number
) {
  let recordsSent = 0;
  let lastIdSent: string | undefined;
  const items = await parseNdjsonStreamToArray<T>(
    stream,
    serializer,
    limit,
    (record) => {
      recordsSent++;
      const id = (record as Record<string, unknown> | null)?.[cursorField];
      if (typeof id === "string") {
        lastIdSent = id;
      }
    }
  );
  return { items, recordsSent, lastIdSent };
}

async function searchAllPages<T>(
  fetchPage: (
    limit: number,
    cursor: string | undefined
  ) => Promise<ReadableStream<Uint8Array>>,
  serializer: core.serialization.Schema<unknown, T>,
  cursorField: string,
  maxResults: number
): Promise<T[]> {
  const results: T[] = [];
  let cursor: string | undefined;

  while (results.length < maxResults) {
    const limit = Math.min(maxResults - results.length, MAX_SEARCH_BATCH_SIZE);
    const page = await readPage(
      await fetchPage(limit, cursor),
      serializer,
      cursorField,
      limit
    );
    results.push(...page.items);

    const isLastPage = page.recordsSent < limit;
    const cursorMoved =
      page.lastIdSent !== undefined && page.lastIdSent !== cursor;
    if (isLastPage || !cursorMoved) {
      break;
    }
    cursor = page.lastIdSent;
  }

  return results;
}

/**
 * Searches traces with filters and returns parsed results
 */
export async function searchTracesWithFilters(
  apiClient: OpikApiClientTemp,
  projectName: string,
  filters: OpikApi.TraceFilterPublic[] | null,
  maxResults: number,
  truncate: boolean,
  exclude?: OpikApi.TraceSearchStreamRequestPublicExcludeItem[]
): Promise<OpikApi.TracePublic[]> {
  return searchAllPages(
    (limit, lastRetrievedId) =>
      apiClient.traces.searchTraces({
        projectName,
        filters: filters ?? undefined,
        limit,
        lastRetrievedId,
        truncate,
        exclude,
      }),
    serialization.TracePublic,
    "id",
    maxResults
  );
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
  const timeout = Number.isNaN(waitForTimeout) ? 0 : waitForTimeout;
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
    if (elapsedTime >= timeout) {
      // Return best attempt results
      return result;
    }

    // Sleep only for the time remaining in the caller's timeout budget.
    await new Promise((resolve) =>
      setTimeout(resolve, Math.min(sleepTime, timeout - elapsedTime))
    );
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
  return searchAllPages(
    (limit, lastRetrievedThreadModelId) =>
      apiClient.traces.searchTraceThreads({
        projectName,
        filters: filters ?? undefined,
        limit,
        lastRetrievedThreadModelId,
        truncate,
      }),
    serialization.TraceThread,
    "thread_model_id",
    maxResults
  );
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
  truncate: boolean,
  exclude?: OpikApi.SpanSearchStreamRequestPublicExcludeItem[]
): Promise<OpikApi.SpanPublic[]> {
  return searchAllPages(
    (limit, lastRetrievedId) =>
      apiClient.spans.searchSpans({
        projectName,
        filters: filters ?? undefined,
        limit,
        lastRetrievedId,
        truncate,
        exclude,
      }),
    serialization.SpanPublic,
    "id",
    maxResults
  );
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
