import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'Switchboard',
  tagline: 'Ultra-low-latency, zero-password encrypted control bridge between mobile and PC',
  favicon: 'img/icon.svg',

  future: {
    v4: true,
  },

  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },
  themes: [
    '@docusaurus/theme-mermaid',
    [
      '@easyops-cn/docusaurus-search-local',
      {
        hashed: true,
        indexBlog: false,
        docsRouteBasePath: '/',
      },
    ],
  ],

  url: 'https://aiyu-ayaan.github.io',
  baseUrl: process.env.NODE_ENV === 'production' ? '/Switchboard/' : '/',

  organizationName: 'aiyu-ayaan',
  projectName: 'Switchboard',

  onBrokenLinks: 'warn',

  stylesheets: [
    {
      href: 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap',
      type: 'text/css',
    },
  ],

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          routeBasePath: '/',
          editUrl: 'https://github.com/aiyu-ayaan/Switchboard/tree/master/docs/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/screenshot_desktop_main.png',
    colorMode: {
      defaultMode: 'dark',
      disableSwitch: true,
      respectPrefersColorScheme: false,
    },
    navbar: {
      title: 'Switchboard',
      logo: {
        alt: 'Switchboard Logo',
        src: 'img/icon.svg',
      },
      items: [
        {
          to: '/features',
          label: 'Features',
          position: 'left',
        },
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          to: '/architecture',
          label: 'Architecture',
          position: 'left',
        },
        {
          to: '/api-protocol',
          label: 'Protocols',
          position: 'left',
        },
        {
          to: '/displays-ddcci',
          label: 'Hardware',
          position: 'left',
        },
        {
          href: 'https://github.com/aiyu-ayaan/Switchboard/releases',
          label: 'Download',
          position: 'right',
        },
        {
          href: 'https://github.com/aiyu-ayaan/Switchboard',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Documentation',
          items: [
            {label: 'Overview & Guide', to: '/overview'},
            {label: 'Getting Started', to: '/getting-started'},
            {label: 'System Architecture', to: '/architecture'},
            {label: 'Security & Pairing', to: '/security-pairing'},
          ],
        },
        {
          title: 'Subsystems',
          items: [
            {label: 'Displays & DDC/CI', to: '/displays-ddcci'},
            {label: 'Audio Mixer & SMTC', to: '/audio-mixer'},
            {label: 'Encrypted File Transfer', to: '/file-transfer'},
            {label: 'Air Mouse Touchpad', to: '/air-mouse'},
            {label: 'Stream Deck Neo', to: '/stream-deck-neo'},
          ],
        },
        {
          title: 'Project & Community',
          items: [
            {label: 'GitHub Repository', href: 'https://github.com/aiyu-ayaan/Switchboard'},
            {label: 'Latest Releases', href: 'https://github.com/aiyu-ayaan/Switchboard/releases'},
            {label: 'Troubleshooting & FAQ', to: '/troubleshooting'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Switchboard. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.dracula,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['bash', 'json', 'yaml', 'go', 'kotlin', 'powershell'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
