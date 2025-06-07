export function getSourceObjValue<T extends object, D = undefined>(
  obj: T,
  path: string,
  defaultValue?: D
): unknown | D {
  if (!obj || typeof path !== "string" || path.trim() === "") {
    return defaultValue as D;
  }

  const keys = path
    .replace(/\[(\w+)\]/g, ".$1")
    .replace(/^\./, "")
    .split(".");

  let result: unknown = obj;

  for (const key of keys) {
    if (typeof result === "object" && result !== null && key in result) {
      result = (result as Record<string, unknown>)[key];
    } else {
      return defaultValue as D;
    }
  }

  return result === undefined ? defaultValue : result;
}
