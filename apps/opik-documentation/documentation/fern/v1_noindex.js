// Inject canonical links on v1 documentation pages pointing to their latest equivalents.
// Prevents duplicate content penalties while consolidating link equity onto the latest docs.
(function () {
  var path = window.location.pathname;
  var match = path.match(/^(\/docs\/opik)\/v1(\/.*)?$/);
  if (!match) return;

  var base = match[1]; // /docs/opik
  var rest = match[2] || "/"; // everything after /v1

  var canonical;

  // Sections whose URL structure changed between v1 and latest
  if (/^\/agent_optimization/.test(rest)) {
    canonical = base + "/development/optimization-runs/overview";
  } else if (/^\/prompt_engineering/.test(rest)) {
    canonical = base + "/prompt-library/getting-started";
  } else if (/^\/opik-university/.test(rest)) {
    canonical = base + "/";
  } else {
    // Tracing, evaluation, self-host, integrations, reference — same URL structure
    canonical = base + rest;
  }

  var link = document.createElement("link");
  link.rel = "canonical";
  link.href = "https://www.comet.com" + canonical;
  document.head.appendChild(link);
})();
