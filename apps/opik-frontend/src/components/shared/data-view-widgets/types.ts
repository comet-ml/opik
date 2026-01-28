import type { SourceData } from "@/lib/data-view";
import { ContextData } from "@/types/custom-view";

/**
 * Converts ContextData (Trace | Thread) to SourceData for data-view framework.
 * This is safe because both Trace and Thread are object types that can be
 * accessed using JSON pointer path syntax, which is what the data-view framework expects.
 */
export function contextDataToSourceData(data: ContextData): SourceData {
  // Create a new object by spreading, which TypeScript recognizes as Record<string, unknown>
  return { ...data };
}
