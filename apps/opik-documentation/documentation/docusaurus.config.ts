import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'Opik Documentation',
  tagline: 'Open source LLM evaluation platform',
  favicon: 'img/favicon.ico',

  // Set the production url of your site here
  url: 'https://www.comet.com',

  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/docs/opik/',

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'comet-ml', // Usually your GitHub org/user name.
  projectName: 'opik', // Usually your repo name.

  onBrokenLinks: 'warn',
  onBrokenMarkdownLinks: 'warn',

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  markdown: {
    "format": "detect"
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          // Please change this to your repo.
          // Remove this to remove the "edit this page" links.
          //editUrl:
          //   'https://github.com/facebook/docusaurus/tree/main/packages/create-docusaurus/templates/shared/',
          routeBasePath: '/', // Set docs as the homepage
        },
        blog: false,
        theme: {
          customCss:  require.resolve('./src/css/custom.scss'),
        },
      } satisfies Preset.Options,
    ],
  ],

  plugins: [
    'docusaurus-plugin-sass',
    [
      require.resolve("docusaurus-plugin-search-local"),
      {
        hashed: true,
        indexPages: true,
        searchResultLimits: 25,
        docsRouteBasePath: "/docs/opik"
      },
    ],
    [
      '@docusaurus/plugin-client-redirects',
      {
        redirects: [
          {
            to: '/self-host/overview',
            from: ['/self-host/self_hosting_opik'],
          },
        ]
      },
    ]
  ],

  themeConfig: {
    // Replace with your project's social card
    // image: 'img/docusaurus-social-card.jpg',
    navbar: {
      title: 'Comet Opik',
      items: [
        {
          to: '/',
          label: 'Guides',
        },
        {
          to: process.env.NODE_ENV === 'development' 
            ? 'http://localhost:8000' 
            : '/python-sdk-reference',
          label: 'Python SDK reference docs',
          position: 'left',
          className: "header-external-link",
          "aria-label": "Python SDK reference docs",
          target: "_blank",
        },
      ],
    },
    
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['bash'],
    },

  } satisfies Preset.ThemeConfig,
};

export default config;
