import toLower from "lodash/toLower";
import trim from "lodash/trim";

export const normalizeErrorInfoFieldKey = (
  key?: string,
): string | undefined => {
  if (!key) return key;

  const normalizedKey = trim(key);
  if (!normalizedKey) return undefined;

  switch (toLower(normalizedKey)) {
    case "exceptiontype":
    case "exception_type":
      return "exception_type";
    case "message":
      return "message";
    case "traceback":
      return "traceback";
    default:
      return normalizedKey;
  }
};
