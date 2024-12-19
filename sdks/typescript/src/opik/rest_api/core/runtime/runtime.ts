interface DenoGlobal {
    version: {
        deno: string;
    };
}

interface BunGlobal {
    version: string;
}

declare const Deno: DenoGlobal;
declare const Bun: BunGlobal;

/**
 * A constant that indicates whether the environment the code is running is a Web Browser.
 */
const isBrowser = typeof window !== "undefined" && typeof window.document !== "undefined";

/**
 * A constant that indicates whether the environment the code is running is a Web Worker.
 */
const isWebWorker =
    typeof self === "object" &&
    // @ts-ignore
    typeof self?.importScripts === "function" &&
    (self.constructor?.name === "DedicatedWorkerGlobalScope" ||
        self.constructor?.name === "ServiceWorkerGlobalScope" ||
        self.constructor?.name === "SharedWorkerGlobalScope");

/**
 * A constant that indicates whether the environment the code is running is Deno.
 */
const isDeno =
    typeof Deno !== "undefined" && typeof Deno.version !== "undefined" && typeof Deno.version.deno !== "undefined";

/**
 * A constant that indicates whether the environment the code is running is Bun.sh.
 */
const isBun = typeof Bun !== "undefined" && typeof Bun.version !== "undefined";

/**
 * A constant that indicates whether the environment the code is running is Node.JS.
 */
const isNode =
    typeof process !== "undefined" &&
    Boolean(process.version) &&
    Boolean(process.versions?.node) &&
    // Deno spoofs process.versions.node, see https://deno.land/std@0.177.0/node/process.ts?s=versions
    !isDeno &&
    !isBun;

/**
 * A constant that indicates whether the environment the code is running is in React-Native.
 * https://github.com/facebook/react-native/blob/main/packages/react-native/Libraries/Core/setUpNavigator.js
 */
const isReactNative = typeof navigator !== "undefined" && navigator?.product === "ReactNative";

/**
 * A constant that indicates whether the environment the code is running is Cloudflare.
 * https://developers.cloudflare.com/workers/runtime-apis/web-standards/#navigatoruseragent
 */
const isCloudflare = typeof globalThis !== "undefined" && globalThis?.navigator?.userAgent === "Cloudflare-Workers";

/**
 * A constant that indicates which environment and version the SDK is running in.
 */
export const RUNTIME: Runtime = evaluateRuntime();

export interface Runtime {
    type: "browser" | "web-worker" | "deno" | "bun" | "node" | "react-native" | "unknown" | "workerd";
    version?: string;
    parsedVersion?: number;
}

function evaluateRuntime(): Runtime {
    if (isBrowser) {
        return {
            type: "browser",
            version: window.navigator.userAgent,
        };
    }

    if (isCloudflare) {
        return {
            type: "workerd",
        };
    }

    if (isWebWorker) {
        return {
            type: "web-worker",
        };
    }

    if (isDeno) {
        return {
            type: "deno",
            version: Deno.version.deno,
        };
    }

    if (isBun) {
        return {
            type: "bun",
            version: Bun.version,
        };
    }

    if (isNode) {
        return {
            type: "node",
            version: process.versions.node,
            parsedVersion: Number(process.versions.node.split(".")[0]),
        };
    }

    if (isReactNative) {
        return {
            type: "react-native",
        };
    }

    return {
        type: "unknown",
    };
}
