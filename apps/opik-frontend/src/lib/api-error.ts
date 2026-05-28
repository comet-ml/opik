import { AxiosError } from "axios";
import get from "lodash/get";

export const getApiErrorMessage = (
  error: AxiosError,
  fallback = "Something went wrong. Please try again.",
): string => {
  const message = get(error, ["response", "data", "message"]);
  if (typeof message === "string" && message.length > 0) return message;

  const errors = get(error, ["response", "data", "errors"]);
  if (Array.isArray(errors) && errors.length > 0) {
    return errors
      .filter((e) => typeof e === "string" && e.length > 0)
      .join("\n");
  }

  return error.message || fallback;
};
