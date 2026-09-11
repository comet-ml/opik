export const flattenObject = <T extends object>(
  obj: T,
  prefix = "",
  result: Record<string, unknown> = {}
): Record<string, unknown> => {
  for (const key in obj) {
    if (Object.prototype.hasOwnProperty.call(obj, key)) {
      const newKey = prefix ? `${prefix}.${key}` : key;
      if (
        typeof obj[key] === "object" &&
        obj[key] !== null &&
        !Array.isArray(obj[key])
      ) {
        flattenObject(obj[key], newKey, result);
      } else {
        result[newKey] = obj[key];
      }
    }
  }
  return result;
};

export const filterNumericValues = (
  obj: Record<string, unknown>
): Record<string, number> => {
  const result: Record<string, number> = {};
  for (const key in obj) {
    if (Object.prototype.hasOwnProperty.call(obj, key)) {
      if (typeof obj[key] === "number") {
        result[key] = obj[key] as number;
      }
    }
  }
  return result;
};
