import { themes as prismThemes } from "prism-react-renderer";
import type { Config } from "@docusaurus/types";
import type * as Preset from "@docusaurus/preset-classic";
import type * as OpenApiPlugin from "docusaurus-plugin-openapi-docs";
import "dotenv/config";

const SEGMENT_WRITE_KEY = process.env.SEGMENT_WRITE_KEY || "";

const config: Config = {
  title: "Opik Documentation",
  tagline: "Open source LLM evaluation platform",
  favicon: "img/favicon.ico",

  // Set the production url of your site here
  url: "https://www.comet.com",

  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: "/docs/opik/",

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: "comet-ml", // Usually your GitHub org/user name.
  projectName: "opik", // Usually your repo name.

  onBrokenLinks: "warn",
  onBrokenMarkdownLinks: "warn",

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: "en",
    locales: ["en"],
  },

  markdown: {
    format: "detect",
  },

  themes: ["docusaurus-theme-openapi-docs"],

  presets: [
    [
      "classic",
      {
        docs: {
          sidebarPath: "./sidebars.ts",
          routeBasePath: "/",
          docItemComponent: "@theme/ApiItem",
        },
        blog: false,
        theme: {
          customCss: require.resolve("./src/css/custom.scss"),
        },
      } satisfies Preset.Options,
    ],
  ],

  plugins: [
    "docusaurus-plugin-sass",
    [
      "docusaurus-plugin-llms-txt",
      {
        title: "Opik documentation",
        description:
          "Opik is an open source LLM evaluation platform that includes a prompt playground, automated evaluation metrics, and a LLM gateway.",
        fullLLMsTxt: true,
      },
    ],
    [
      require.resolve("docusaurus-plugin-search-local"),
      {
        hashed: true,
        indexPages: true,
        searchResultLimits: 25,
        docsRouteBasePath: "/docs/opik",
      },
    ],
    [
      "@docusaurus/plugin-client-redirects",
      {
        redirects: [
          {
            to: "/self-host/overview",
            from: ["/self-host/self_hosting_opik"],
          },
          {
            to: "/prompt_engineering/playground",
            from: ["/evaluation/playground"],
          },
          {
            to: "/prompt_engineering/prompt_management",
            from: ["/library/prompt_management"],
          },
          {
            to: "/prompt_engineering/managing_prompts_in_code",
            from: ["/library/managing_prompts_in_code"],
          },
        ],
      },
    ],
    [
      "docusaurus-plugin-openapi-docs",
      {
        id: "api",
        docsPluginId: "classic",
        config: {
          opik: {
            specPath: "rest_api/opik.yaml",
            outputDir: "docs/reference/rest_api",
            hideSendButton: true,
            sidebarOptions: {
              groupPathsBy: "tag",
            },
          } satisfies OpenApiPlugin.Options,
        },
      },
    ],
  ],

  customFields: {
    segmentWriteKey: SEGMENT_WRITE_KEY,
  },

  themeConfig: {
    // Replace with your project's social card
    // image: 'img/docusaurus-social-card.jpg',
    navbar: {
      title: "Comet Opik",
      items: [
        {
          type: "docSidebar",
          to: "/",
          label: "Guides",
          sidebarId: "guide_sidebar",
          position: "left",
          docId: "home",
        },
        {
          type: "docSidebar",
          to: "/reference/rest_api",
          label: "REST API",
          sidebarId: "rest_api",
          position: "left",
        },
        {
          to: process.env.NODE_ENV === "development" ? "http://localhost:8000" : "/python-sdk-reference",
          label: "Python SDK reference docs",
          position: "left",
          className: "header-external-link",
          "aria-label": "Python SDK reference docs",
          target: "_blank",
        },
      ],
    },

    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ["bash"],
    },

    languageTabs: [
      {
        tabName: "cURL",
        highlight: "bash",
        language: "curl",
      },
      {
        tabName: "Python",
        highlight: "python",
        language: "python",
      },
    ],
  } satisfies Preset.ThemeConfig,
};

export default config;
