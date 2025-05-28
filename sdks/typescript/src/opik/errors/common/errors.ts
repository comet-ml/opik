import { OpikError } from "../BaseError";
import { commonErrorMessages } from "./errorMessages";

const commonErrorCodes = {
  JSON_PARSE_ERROR: "JSON_PARSE_ERROR",
  JSON_NOT_ARRAY: "JSON_NOT_ARRAY",
  JSON_ITEM_NOT_OBJECT: "JSON_ITEM_NOT_OBJECT",
};

export class JsonParseError extends OpikError {
  constructor(originalError: unknown) {
    const parsedError =
      originalError instanceof Error
        ? originalError
        : new Error(String(originalError));

    super({
      message: commonErrorMessages.JSON_PARSE_ERROR,
      code: commonErrorCodes.JSON_PARSE_ERROR,
      originalError: parsedError,
    });
  }
}

export class JsonNotArrayError extends OpikError {
  constructor(receivedType?: string) {
    super({
      message: commonErrorMessages.JSON_NOT_ARRAY(receivedType),
      code: commonErrorCodes.JSON_NOT_ARRAY,
    });
  }
}

export class JsonItemNotObjectError extends OpikError {
  constructor(index: number, receivedType?: string) {
    super({
      message: commonErrorMessages.JSON_ITEM_NOT_OBJECT(index, receivedType),
      code: commonErrorCodes.JSON_ITEM_NOT_OBJECT,
    });
  }
}
