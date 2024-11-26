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
exports.requestWithRetries = void 0;
const INITIAL_RETRY_DELAY = 1000; // in milliseconds
const MAX_RETRY_DELAY = 60000; // in milliseconds
const DEFAULT_MAX_RETRIES = 2;
const JITTER_FACTOR = 0.2; // 20% random jitter
function addJitter(delay) {
    // Generate a random value between -JITTER_FACTOR and +JITTER_FACTOR
    const jitterMultiplier = 1 + (Math.random() * 2 - 1) * JITTER_FACTOR;
    return delay * jitterMultiplier;
}
function requestWithRetries(requestFn, maxRetries = DEFAULT_MAX_RETRIES) {
    return __awaiter(this, void 0, void 0, function* () {
        let response = yield requestFn();
        for (let i = 0; i < maxRetries; ++i) {
            if ([408, 409, 429].includes(response.status) || response.status >= 500) {
                // Calculate base delay using exponential backoff (in milliseconds)
                const baseDelay = Math.min(INITIAL_RETRY_DELAY * Math.pow(2, i), MAX_RETRY_DELAY);
                // Add jitter to the delay
                const delayWithJitter = addJitter(baseDelay);
                yield new Promise((resolve) => setTimeout(resolve, delayWithJitter));
                response = yield requestFn();
            }
            else {
                break;
            }
        }
        return response;
    });
}
exports.requestWithRetries = requestWithRetries;
