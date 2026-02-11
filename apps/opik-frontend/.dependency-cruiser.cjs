/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    // ═══════════════════════════════════════════════════════════════
    // CIRCULAR DEPENDENCY RULES
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-circular",
      severity: "error",
      comment: "Circular dependencies lead to maintenance nightmares",
      from: {},
      to: {
        circular: true,
      },
    },

    // ═══════════════════════════════════════════════════════════════
    // COMPONENT LAYER HIERARCHY (STRICT)
    // ui → shared → pages-shared → pages (one-way only)
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-ui-importing-shared",
      severity: "error",
      comment: "Base UI components must not import from shared components",
      from: { path: "^src/components/ui/" },
      to: { path: "^src/components/(shared|pages-shared|pages)/" },
    },
    {
      name: "no-shared-importing-pages",
      severity: "error",
      comment: "Shared components must not import from page-specific components",
      from: { path: "^src/components/shared/" },
      to: { path: "^src/components/(pages-shared|pages)/" },
    },
    {
      name: "no-pages-shared-importing-pages",
      severity: "error",
      comment: "Pages-shared components must not import from specific pages",
      from: { path: "^src/components/pages-shared/" },
      to: { path: "^src/components/pages/" },
    },
    {
      name: "no-cross-page-imports",
      severity: "error",
      comment: "Pages should not import from other pages directly",
      from: { path: "^src/components/pages/([^/]+)/" },
      to: {
        path: "^src/components/pages/([^/]+)/",
        pathNot: "^src/components/pages/$1/", // Allow same page folder
      },
    },

    // ═══════════════════════════════════════════════════════════════
    // API LAYER ISOLATION
    // Exception: use-toast.ts (it's a hook, not a component)
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-api-importing-components",
      severity: "error",
      comment:
        "API layer must not import React components (exception: use-toast.ts)",
      from: { path: "^src/api/" },
      to: {
        path: "^src/components/",
        // Only allow use-toast.ts - it's essentially a hook
        pathNot: ["^src/components/ui/use-toast\\.ts$"],
      },
    },

    // ═══════════════════════════════════════════════════════════════
    // STORE RULES
    // Exception: PluginsStore can import plugin-related components
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-store-importing-components",
      severity: "error",
      comment: "Stores must not import components (exception: PluginsStore)",
      from: { path: "^src/store/", pathNot: "^src/store/PluginsStore\\.ts$" },
      to: { path: "^src/components/" },
    },

    // ═══════════════════════════════════════════════════════════════
    // HOOKS RULES
    // Exception: theme-provider and feature-toggles-provider
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-hooks-importing-components",
      severity: "error",
      comment:
        "Hooks must not import components (exception: app-level providers, ConfirmDialog)",
      from: { path: "^src/hooks/" },
      to: {
        path: "^src/components/",
        // Allow importing providers, use-toast (hook), and ConfirmDialog (for useNavigationBlocker)
        pathNot: [
          "^src/components/ui/use-toast\\.ts$",
          "^src/components/theme-provider\\.tsx$",
          "^src/components/feature-toggles-provider\\.tsx$",
          "^src/components/server-sync-provider\\.tsx$",
          "^src/components/shared/ConfirmDialog/",
        ],
      },
    },

    // ═══════════════════════════════════════════════════════════════
    // TYPES & CONSTANTS ISOLATION
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-types-with-side-effects",
      severity: "error",
      comment: "Types folder should only contain type definitions",
      from: { path: "^src/types/" },
      to: {
        path: "^src/(components|api|store|hooks)/",
      },
    },
    {
      name: "no-constants-importing-runtime",
      severity: "error",
      comment: "Constants should not import runtime code",
      from: { path: "^src/constants/" },
      to: {
        path: "^src/(components|api|store|hooks)/",
        // Allowed exceptions:
        pathNot: [
          "\\.py$", // Python scripts used as template strings
          "integration-logs\\.ts$", // Static log content
          "^src/components/ui/tag\\.tsx$", // Tag component for experiments
        ],
      },
    },

    // ═══════════════════════════════════════════════════════════════
    // LODASH IMPORT RULES
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-lodash-default-import",
      severity: "error",
      comment:
        "Use individual lodash imports for tree-shaking (import isString from 'lodash/isString')",
      from: {},
      to: {
        path: "^lodash$",
      },
    },

    // ═══════════════════════════════════════════════════════════════
    // PLUGINS ISOLATION (STRICT)
    // Plugins can import from project, but project cannot import from plugins
    // Exception: PluginsStore.ts for plugin registration
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-project-importing-plugins",
      severity: "error",
      comment: "Project code must not import from plugins",
      from: {
        path: "^src/",
        pathNot: [
          "^src/plugins/",
          // Only PluginsStore can import plugins for registration
          "^src/store/PluginsStore\\.ts$",
        ],
      },
      to: {
        path: "^src/plugins/",
      },
    },

    // ═══════════════════════════════════════════════════════════════
    // GENERAL BEST PRACTICES
    // ═══════════════════════════════════════════════════════════════
    {
      name: "no-orphans",
      severity: "info",
      comment: "Orphan modules are not reachable from entry points",
      from: {
        orphan: true,
        pathNot: [
          "(^|/)\\.[^/]+\\.(js|cjs|mjs|ts|json)$", // dot files
          "\\.d\\.ts$", // TypeScript declaration files
          "(^|/)tsconfig\\.json$",
          "(^|/)vite\\.config\\.",
          "\\.test\\.(ts|tsx)$", // test files
          "\\.spec\\.(ts|tsx)$",
          "__mocks__",
          "e2e/",
        ],
      },
      to: {},
    },
    {
      name: "no-deprecated-core",
      severity: "warn",
      comment: "Avoid using deprecated Node.js core modules",
      from: {},
      to: {
        dependencyTypes: ["core"],
        path: ["^punycode$", "^domain$", "^constants$", "^sys$", "^_stream"],
      },
    },
    {
      name: "not-to-unresolvable",
      severity: "error",
      comment: "Imports must be resolvable",
      from: {},
      to: {
        couldNotResolve: true,
      },
    },
    {
      name: "no-duplicate-dep-types",
      severity: "warn",
      comment:
        "Package should not be in both dependencies and devDependencies",
      from: {},
      to: {
        moreThanOneDependencyType: true,
        dependencyTypesNot: ["type-only"],
      },
    },
  ],

  options: {
    doNotFollow: {
      path: ["node_modules"],
    },
    exclude: {
      path: [
        "node_modules",
        "\\.test\\.(ts|tsx)$",
        "\\.spec\\.(ts|tsx)$",
        "__tests__",
        "__mocks__",
        "e2e/",
        "\\.d\\.ts$",
      ],
    },
    includeOnly: {
      path: "^src/",
    },
    tsPreCompilationDeps: true,
    tsConfig: {
      fileName: "tsconfig.json",
    },
    enhancedResolveOptions: {
      exportsFields: ["exports"],
      conditionNames: ["import", "require", "node", "default"],
      mainFields: ["module", "main", "types"],
    },
    reporterOptions: {
      dot: {
        collapsePattern: "node_modules/(@[^/]+/[^/]+|[^/]+)",
        theme: {
          graph: {
            splines: "ortho",
            rankdir: "TB",
            ranksep: "1",
          },
          modules: [
            {
              criteria: { source: "^src/api/" },
              attributes: { fillcolor: "#ffcccc" },
            },
            {
              criteria: { source: "^src/components/ui/" },
              attributes: { fillcolor: "#ccffcc" },
            },
            {
              criteria: { source: "^src/components/shared/" },
              attributes: { fillcolor: "#ccccff" },
            },
            {
              criteria: { source: "^src/components/pages-shared/" },
              attributes: { fillcolor: "#ffffcc" },
            },
            {
              criteria: { source: "^src/components/pages/" },
              attributes: { fillcolor: "#ffccff" },
            },
            {
              criteria: { source: "^src/hooks/" },
              attributes: { fillcolor: "#ccffff" },
            },
            {
              criteria: { source: "^src/store/" },
              attributes: { fillcolor: "#ffddaa" },
            },
            {
              criteria: { source: "^src/lib/" },
              attributes: { fillcolor: "#dddddd" },
            },
            {
              criteria: { source: "^src/plugins/" },
              attributes: { fillcolor: "#e6ccff" },
            },
          ],
          dependencies: [
            {
              criteria: { resolved: "^src/api/" },
              attributes: { color: "#cc0000" },
            },
            {
              criteria: { resolved: "^src/store/" },
              attributes: { color: "#ff8800" },
            },
            {
              criteria: { resolved: "^src/plugins/" },
              attributes: { color: "#9933ff" },
            },
          ],
        },
      },
      archi: {
        collapsePattern: "^src/([^/]+)/",
        theme: {
          graph: { rankdir: "TB" },
        },
      },
    },
  },
};