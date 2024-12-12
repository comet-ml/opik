import { loadConfig, OpikConfig } from "./config";

interface OpikClient {
  logTrace(name: string, data?: Record<string, any>): void;
  logSpan<T>(
    name: string,
    fn: () => Promise<T>,
    data?: Record<string, any>
  ): Promise<T>;
}

export function createClient(explicitConfig?: Partial<OpikConfig>): OpikClient {
  const config = loadConfig(explicitConfig);
  const tracer = createTracer(config);

  return {
    logTrace(name, data) {},
    logSpan(name, fn, data) {
      return tracer.logSpan(name, fn, data);
    },
  };
}
