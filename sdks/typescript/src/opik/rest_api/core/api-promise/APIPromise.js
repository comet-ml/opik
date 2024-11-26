"use strict";
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.APIPromise = void 0;
/**
 * APIPromise wraps a Promise that resolves with an APIResponse.
 * It provides convenient methods for handling both successful responses and errors.
 *
 * By default, when awaited, it will return just the response body data.
 * Use the `asRaw()` method to get access to both the response data and headers.
 *
 * @example
 * // Get just the response data
 * const data = await apiPromise;
 *
 * // Get response with headers
 * const { data, headers } = await apiPromise.asRaw();
 *
 * @template T The type of the successful response body
 */
class APIPromise extends Promise {
    constructor(responsePromise, executor) {
        super(executor);
        this.responsePromise = responsePromise;
    }
    asRaw() {
        return __awaiter(this, void 0, void 0, function* () {
            const response = yield this.responsePromise;
            if (!response.ok) {
                throw response.error;
            }
            return {
                data: response.body,
                headers: response.headers,
            };
        });
    }
    static from(responsePromise) {
        return new APIPromise(responsePromise, (resolve, reject) => {
            responsePromise
                .then((response) => {
                if (response.ok) {
                    resolve(response.body);
                }
                else {
                    reject(response.error);
                }
            })
                .catch(reject);
        });
    }
}
exports.APIPromise = APIPromise;
