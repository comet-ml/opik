"use strict";
/**
 * This file was auto-generated by Fern from our API Definition.
 */
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (k !== "default" && Object.prototype.hasOwnProperty.call(mod, k)) __createBinding(result, mod, k);
    __setModuleDefault(result, mod);
    return result;
};
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.OpikApiClient = void 0;
const environments = __importStar(require("./environments"));
const core = __importStar(require("./core"));
const url_join_1 = __importDefault(require("url-join"));
const errors = __importStar(require("./errors/index"));
const Client_1 = require("./api/resources/systemUsage/client/Client");
const Client_2 = require("./api/resources/check/client/Client");
const Client_3 = require("./api/resources/datasets/client/Client");
const Client_4 = require("./api/resources/experiments/client/Client");
const Client_5 = require("./api/resources/feedbackDefinitions/client/Client");
const Client_6 = require("./api/resources/projects/client/Client");
const Client_7 = require("./api/resources/prompts/client/Client");
const Client_8 = require("./api/resources/spans/client/Client");
const Client_9 = require("./api/resources/traces/client/Client");
class OpikApiClient {
    constructor(_options = {}) {
        this._options = _options;
    }
    /**
     * @param {OpikApiClient.RequestOptions} requestOptions - Request-specific configuration.
     *
     * @example
     *     await client.isAlive()
     */
    isAlive(requestOptions) {
        return core.APIPromise.from((() => __awaiter(this, void 0, void 0, function* () {
            var _a;
            const _response = yield core.fetcher({
                url: (0, url_join_1.default)((_a = (yield core.Supplier.get(this._options.environment))) !== null && _a !== void 0 ? _a : environments.OpikApiEnvironment.Default, "is-alive/ping"),
                method: "GET",
                headers: Object.assign({ "X-Fern-Language": "JavaScript", "X-Fern-Runtime": core.RUNTIME.type, "X-Fern-Runtime-Version": core.RUNTIME.version }, requestOptions === null || requestOptions === void 0 ? void 0 : requestOptions.headers),
                contentType: "application/json",
                requestType: "json",
                timeoutMs: (requestOptions === null || requestOptions === void 0 ? void 0 : requestOptions.timeoutInSeconds) != null ? requestOptions.timeoutInSeconds * 1000 : 60000,
                maxRetries: requestOptions === null || requestOptions === void 0 ? void 0 : requestOptions.maxRetries,
                abortSignal: requestOptions === null || requestOptions === void 0 ? void 0 : requestOptions.abortSignal,
            });
            if (_response.ok) {
                return {
                    ok: _response.ok,
                    body: _response.body,
                    headers: _response.headers,
                };
            }
            if (_response.error.reason === "status-code") {
                throw new errors.OpikApiError({
                    statusCode: _response.error.statusCode,
                    body: _response.error.body,
                });
            }
            switch (_response.error.reason) {
                case "non-json":
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.rawBody,
                    });
                case "timeout":
                    throw new errors.OpikApiTimeoutError("Timeout exceeded when calling GET /is-alive/ping.");
                case "unknown":
                    throw new errors.OpikApiError({
                        message: _response.error.errorMessage,
                    });
            }
        }))());
    }
    /**
     * @param {OpikApiClient.RequestOptions} requestOptions - Request-specific configuration.
     *
     * @example
     *     await client.version()
     */
    version(requestOptions) {
        return core.APIPromise.from((() => __awaiter(this, void 0, void 0, function* () {
            var _a;
            const _response = yield core.fetcher({
                url: (0, url_join_1.default)((_a = (yield core.Supplier.get(this._options.environment))) !== null && _a !== void 0 ? _a : environments.OpikApiEnvironment.Default, "is-alive/ver"),
                method: "GET",
                headers: Object.assign({ "X-Fern-Language": "JavaScript", "X-Fern-Runtime": core.RUNTIME.type, "X-Fern-Runtime-Version": core.RUNTIME.version }, requestOptions === null || requestOptions === void 0 ? void 0 : requestOptions.headers),
                contentType: "application/json",
                requestType: "json",
                timeoutMs: (requestOptions === null || requestOptions === void 0 ? void 0 : requestOptions.timeoutInSeconds) != null ? requestOptions.timeoutInSeconds * 1000 : 60000,
                maxRetries: requestOptions === null || requestOptions === void 0 ? void 0 : requestOptions.maxRetries,
                abortSignal: requestOptions === null || requestOptions === void 0 ? void 0 : requestOptions.abortSignal,
            });
            if (_response.ok) {
                return {
                    ok: _response.ok,
                    body: _response.body,
                    headers: _response.headers,
                };
            }
            if (_response.error.reason === "status-code") {
                throw new errors.OpikApiError({
                    statusCode: _response.error.statusCode,
                    body: _response.error.body,
                });
            }
            switch (_response.error.reason) {
                case "non-json":
                    throw new errors.OpikApiError({
                        statusCode: _response.error.statusCode,
                        body: _response.error.rawBody,
                    });
                case "timeout":
                    throw new errors.OpikApiTimeoutError("Timeout exceeded when calling GET /is-alive/ver.");
                case "unknown":
                    throw new errors.OpikApiError({
                        message: _response.error.errorMessage,
                    });
            }
        }))());
    }
    get systemUsage() {
        var _a;
        return ((_a = this._systemUsage) !== null && _a !== void 0 ? _a : (this._systemUsage = new Client_1.SystemUsage(this._options)));
    }
    get check() {
        var _a;
        return ((_a = this._check) !== null && _a !== void 0 ? _a : (this._check = new Client_2.Check(this._options)));
    }
    get datasets() {
        var _a;
        return ((_a = this._datasets) !== null && _a !== void 0 ? _a : (this._datasets = new Client_3.Datasets(this._options)));
    }
    get experiments() {
        var _a;
        return ((_a = this._experiments) !== null && _a !== void 0 ? _a : (this._experiments = new Client_4.Experiments(this._options)));
    }
    get feedbackDefinitions() {
        var _a;
        return ((_a = this._feedbackDefinitions) !== null && _a !== void 0 ? _a : (this._feedbackDefinitions = new Client_5.FeedbackDefinitions(this._options)));
    }
    get projects() {
        var _a;
        return ((_a = this._projects) !== null && _a !== void 0 ? _a : (this._projects = new Client_6.Projects(this._options)));
    }
    get prompts() {
        var _a;
        return ((_a = this._prompts) !== null && _a !== void 0 ? _a : (this._prompts = new Client_7.Prompts(this._options)));
    }
    get spans() {
        var _a;
        return ((_a = this._spans) !== null && _a !== void 0 ? _a : (this._spans = new Client_8.Spans(this._options)));
    }
    get traces() {
        var _a;
        return ((_a = this._traces) !== null && _a !== void 0 ? _a : (this._traces = new Client_9.Traces(this._options)));
    }
}
exports.OpikApiClient = OpikApiClient;