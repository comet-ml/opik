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
 * Parses a filter string using OpikQueryLanguage and converts to TraceFilterPublic format
 */
export function parseFilterString(
  filterString?: string
): OpikApi.TraceFilterPublic[] | null {
  if (!filterString) {
    return null;
  }

  const oql = new OpikQueryLanguage(filterString);
  const filterExpressions = oql.getFilterExpressions();

  if (!filterExpressions) {
    return null;
  }

  // Convert FilterExpression[] to TraceFilterPublic[]
  // FilterExpression has: { field, operator, value, key?, type? }
  // TraceFilterPublic has: { field?, operator?, value?, key? }
  // The operator is validated by OpikQueryLanguage and is guaranteed to be a valid operator string
  return filterExpressions.map((expr) => {
    const filter: OpikApi.TraceFilterPublic = {
      field: expr.field,
      operator: expr.operator as OpikApi.TraceFilterPublicOperator,
      value: expr.value,
    };

    if (expr.key) {
      filter.key = expr.key;
    }

    return filter;
  });
}
