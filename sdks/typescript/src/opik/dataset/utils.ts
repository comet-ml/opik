export function stringifyWithSortedKeys(obj: Record<string, unknown>): string {
  // Recursive function to sort keys at all levels
  function sortKeys(value: unknown): unknown {
    if (value && typeof value === "object" && !Array.isArray(value)) {
      const sortedObj: Record<string, unknown> = {};
      Object.keys(value as Record<string, unknown>)
        .sort()
        .forEach((key) => {
          sortedObj[key] = sortKeys((value as Record<string, unknown>)[key]);
        });
      return sortedObj;
    } else if (Array.isArray(value)) {
      return value.map(sortKeys);
    }
    return value;
  }

  const sortedObj = sortKeys(obj);
  return JSON.stringify(sortedObj);
}
