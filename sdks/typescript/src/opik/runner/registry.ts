/* eslint-disable @typescript-eslint/no-explicit-any */

export interface Param {
  name: string;
  type: string;
}

export interface RegistryEntry {
  func: (...args: any[]) => any;
  name: string;
  project: string;
  params: Param[];
  docstring: string;
}

type RegistrationListener = (name: string) => void;

const REGISTRY = new Map<string, RegistryEntry>();
const listeners: RegistrationListener[] = [];

export function register(entry: RegistryEntry): void {
  REGISTRY.set(entry.name, entry);
  for (const listener of listeners) {
    listener(entry.name);
  }
}

export function onRegister(listener: RegistrationListener): void {
  listeners.push(listener);
}

export function getAll(): Map<string, RegistryEntry> {
  return new Map(REGISTRY);
}

const PARAM_PATTERN = /^(?:async\s+)?(?:function\s+\w*\s*)?\(([^)]*)\)/;
const ARROW_PATTERN = /^(?:async\s+)?(?:\(([^)]*)\)|(\w+))\s*=>/;

// eslint-disable-next-line @typescript-eslint/no-unsafe-function-type
export function extractParams(fn: Function): Param[] {
  const src = fn.toString();
  const match = PARAM_PATTERN.exec(src) || ARROW_PATTERN.exec(src);
  if (!match) return [];

  const raw = match[1] ?? match[2] ?? "";
  if (!raw.trim()) return [];

  return raw
    .split(",")
    .map((p) => p.trim())
    .filter((p) => p.length > 0)
    .map((p) => {
      const name = p
        .replace(/=.*$/, "")
        .replace(/:\s*.*$/, "")
        .replace(/^\.\.\.|[?]$/g, "")
        .trim();
      return { name, type: "string" };
    })
    .filter((p) => p.name.length > 0);
}
