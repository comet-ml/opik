import { loadConfig } from "./Config";

/**
 * Runtime state for whether tracing is active.
 *
 * The instrumentation layer (the `track` decorator and integrations) consults
 * this before creating traces/spans. The default is derived once from the
 * `trackDisable` config (`OPIK_TRACK_DISABLE` env var / config file) and cached,
 * so the hot path never re-reads the environment or config file. A runtime
 * override set via `setTracingActive` takes precedence over the config default
 * until `resetToConfigDefault` is called.
 */
class TracingRuntimeConfig {
  private tracingActive: boolean | null = null;

  setTracingActive(active: boolean): void {
    this.tracingActive = active;
  }

  resetToConfigDefault(): void {
    this.tracingActive = null;
  }

  isTracingActive(): boolean {
    if (this.tracingActive !== null) {
      return this.tracingActive;
    }

    try {
      this.tracingActive = !loadConfig().trackDisable;
      return this.tracingActive;
    } catch {
      // If the config can't be resolved, fall back to active without caching
      // so a later, well-formed read can still derive the real default.
      return true;
    }
  }
}

const runtimeConfig = new TracingRuntimeConfig();

/**
 * Enable or disable tracing at runtime. Overrides the `trackDisable` config
 * default until {@link resetTracingToConfigDefault} is called.
 */
export function setTracingActive(active: boolean): void {
  runtimeConfig.setTracingActive(active);
}

/**
 * Whether tracing is currently active. Returns the runtime override if one was
 * set, otherwise the (cached) `!trackDisable` config default.
 */
export function isTracingActive(): boolean {
  return runtimeConfig.isTracingActive();
}

/**
 * Clear any runtime override so the `trackDisable` config default applies again.
 */
export function resetTracingToConfigDefault(): void {
  runtimeConfig.resetToConfigDefault();
}
