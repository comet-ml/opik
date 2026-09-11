import { AxiosError } from "axios";
import get from "lodash/get";

export const extractErrorMessage = (error: AxiosError): string => {
  const errors = get(error, ["response", "data", "errors"]);
  return (
    (Array.isArray(errors) && errors.length > 0
      ? errors.join(", ")
      : undefined) ??
    get(error, ["response", "data", "message"]) ??
    error.message ??
    "An unknown error occurred. Please try again later."
  );
};
