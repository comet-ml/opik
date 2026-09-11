import type { ValidationError } from "../../Schema.js";
import { stringifyValidationError } from "./stringifyValidationErrors.js";

export class JsonError extends Error {
    constructor(public readonly errors: ValidationError[]) {
        super(errors.map(stringifyValidationError).join("; "));
        Object.setPrototypeOf(this, JsonError.prototype);
    }
}
