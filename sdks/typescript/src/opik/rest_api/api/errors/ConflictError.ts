/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as errors from "../../errors/index";

export class ConflictError extends errors.OpikApiError {
    constructor(body?: unknown) {
        super({
            message: "ConflictError",
            statusCode: 409,
            body: body,
        });
        Object.setPrototypeOf(this, ConflictError.prototype);
    }
}