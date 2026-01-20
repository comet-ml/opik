import type { ValidationError } from "../../Schema.js";

export function stringifyValidationError(error: ValidationError): string {
    if (error.path.length === 0) {
        return error.message;
    }
    return `${error.path.join(" -> ")}: ${error.message}`;
}
