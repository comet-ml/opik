import { ValidationError } from "../../Schema";
import { stringifyValidationError } from "./stringifyValidationErrors";

export class JsonError extends Error {
    constructor(public readonly errors: ValidationError[]) {
        super(errors.map(stringifyValidationError).join("; "));
        Object.setPrototypeOf(this, JsonError.prototype);
    }
}
