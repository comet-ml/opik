// Polyfill Promise.withResolvers for pdfjs-dist (ES2024 feature, Node < 22)
if (typeof (Promise as any).withResolvers === 'undefined') {
  (Promise as any).withResolvers = function<T>() {
    let resolve: (value: T | PromiseLike<T>) => void;
    let reject: (reason?: unknown) => void;
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve: resolve!, reject: reject! };
  };
}

import '@testing-library/jest-dom/vitest'

// Mock Worker for pdfjs
global.Worker = class Worker {
    constructor(public url: string | URL) {}
    postMessage() {}
    terminate() {}
    addEventListener() {}
    removeEventListener() {}
    dispatchEvent() { return true; }
  } as any;