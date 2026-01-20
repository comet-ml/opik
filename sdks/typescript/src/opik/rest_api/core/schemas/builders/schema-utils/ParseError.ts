import type { ValidationError } from "../../Schema.js";
import { stringifyValidationError } from "./stringifyValidationErrors.js";

export class ParseError extends Error {
    constructor(public readonly errors: ValidationError[]) {
        super(errors.map(stringifyValidationError).join("; "));
        Object.setPrototypeOf(this, ParseError.prototype);
    }
}
