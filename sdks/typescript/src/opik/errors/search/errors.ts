import { OpikError } from "../BaseError";

const searchErrorCodes = {
  SEARCH_TIMEOUT: "SEARCH_TIMEOUT",
};

export class SearchTimeoutError extends OpikError {
  constructor(message: string) {
    super({
      message,
      code: searchErrorCodes.SEARCH_TIMEOUT,
    });
  }
}
