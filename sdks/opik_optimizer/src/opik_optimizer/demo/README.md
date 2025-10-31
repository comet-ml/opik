## Demo Package Deprecation

The modules under `opik_optimizer.demo` were early experiments for managing LiteLLM caches.
They’ve been superseded by `opik_optimizer.cache_config.initialize_cache`, which provides the
same functionality while honoring environment overrides.

Prefer importing `opik_optimizer.cache_config` in new code. The demo helpers emit a `DeprecationWarning`
when called and will be removed in a future release.
