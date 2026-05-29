// Prevent v1 documentation pages from being indexed by search engines.
// v1 content is preserved for users who need it via the version switcher,
// but should not compete with latest docs in search results.
(function () {
  if (!window.location.pathname.match(/\/docs\/opik\/v1(\/|$)/)) return;
  var meta = document.createElement("meta");
  meta.name = "robots";
  meta.content = "noindex,follow";
  document.head.appendChild(meta);
})();
