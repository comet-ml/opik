// Polyfill Promise.withResolvers for pdfjs-dist (ES2024 feature, Node < 22)
if (typeof (Promise as any).withResolvers === 'undefined') {
  (Promise as any).withResolvers = function<T>() {
    let resolve: (value: T | PromiseLike<T>) => void;
    let reject: (reason?: unknown) => void;
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve: resolve!, reject: reject! };
  };
}

// happy-dom 20.5.0 (bumped for CVE-2025-61927, #5082) exposes a global `localStorage` in the
// vitest environment whose `clear()`/`key()`/`length` are missing, so every test that calls
// `localStorage.clear()` throws before it runs. Install a spec-complete in-memory Storage so the
// whole localStorage-backed test layer (pinned projects/workspaces, recent workspaces, …) runs.
if (typeof globalThis.localStorage?.clear !== 'function') {
  class MemoryStorage {
    private store = new Map<string, string>();
    get length() { return this.store.size; }
    clear() { this.store.clear(); }
    getItem(key: string) { return this.store.has(key) ? this.store.get(key)! : null; }
    key(index: number) { return Array.from(this.store.keys())[index] ?? null; }
    removeItem(key: string) { this.store.delete(key); }
    setItem(key: string, value: string) { this.store.set(key, String(value)); }
  }
  const storage = new MemoryStorage() as unknown as Storage;
  Object.defineProperty(globalThis, 'localStorage', {
    value: storage,
    configurable: true,
    writable: true,
  });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', {
      value: storage,
      configurable: true,
      writable: true,
    });
  }
}

import '@testing-library/jest-dom/vitest'
import { vi } from 'vitest'

// Mock react-h5-audio-player
vi.mock('react-h5-audio-player', () => ({
  default: vi.fn(() => null),
}))

// Mock Worker for pdfjs
global.Worker = class Worker {
    constructor(public url: string | URL) {}
    postMessage() {}
    terminate() {}
    addEventListener() {}
    removeEventListener() {}
    dispatchEvent() { return true; }
  } as any;