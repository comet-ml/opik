import { v4 as uuidv4 } from "uuid";

export type KeyValueEntry = {
  key: string;
  value: string;
  id: string;
};

/**
 * Converts header array from form state to API-compatible object format.
 *
 * Three cases:
 * 1. Non-empty array → Convert to object, filtering empty keys
 * 2. Empty array when editing → Return {} to clear headers from backend
 * 3. Empty array when creating → Return undefined (don't send headers field)
 */
export function convertHeadersForAPI(
  headersArray: Array<{ key: string; value: string }> | undefined,
  isEditing: boolean,
): Record<string, string> | undefined {
  if (headersArray === undefined) {
    return undefined;
  }

  if (headersArray.length > 0) {
    return headersArray.reduce<Record<string, string>>((acc, header) => {
      const trimmedKey = header.key.trim();
      if (trimmedKey) {
        acc[trimmedKey] = header.value;
      }
      return acc;
    }, {});
  }

  if (isEditing) {
    return {};
  }

  return undefined;
}

/**
 * Converts a key/value array to a JSON-encoded Map<String,String> string for
 * storage in the Custom LLM provider's `configuration.url_query_params` slot.
 * Returns undefined when nothing is configured so the key is omitted on create
 * (the backend decorator treats missing/blank as "no query params to append").
 */
export function queryParamsArrayToConfigString(
  queryParamsArray: Array<{ key: string; value: string }> | undefined,
): string | undefined {
  if (!queryParamsArray || queryParamsArray.length === 0) {
    return undefined;
  }
  const filtered = queryParamsArray.reduce<Record<string, string>>(
    (acc, entry) => {
      const trimmedKey = entry.key.trim();
      if (trimmedKey) {
        acc[trimmedKey] = entry.value;
      }
      return acc;
    },
    {},
  );
  return Object.keys(filtered).length > 0
    ? JSON.stringify(filtered)
    : undefined;
}

/** Inverse of queryParamsArrayToConfigString for loading existing providers. */
export function configStringToQueryParamsArray(
  raw: string | undefined,
): KeyValueEntry[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as Record<string, string>;
    return Object.entries(parsed).map(([key, value]) => ({
      key,
      value,
      id: uuidv4(),
    }));
  } catch {
    return [];
  }
}
