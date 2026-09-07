import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'intro',
    {
      type: 'category',
      label: 'Getting Started',
      collapsed: false,
      items: [
        'overview',
        'features',
        'getting-started',
      ],
    },
    {
      type: 'category',
      label: 'Architecture & Security',
      collapsed: false,
      items: [
        'architecture',
        'discovery',
        'security-pairing',
        'api-protocol',
      ],
    },
    {
      type: 'category',
      label: 'Hardware & Subsystems',
      collapsed: false,
      items: [
        'displays-ddcci',
        'audio-mixer',
        'air-mouse',
        'stream-deck-neo',
        'file-transfer',
        'wifi-camera',
      ],
    },
    {
      type: 'category',
      label: 'Operations',
      collapsed: false,
      items: [
        'troubleshooting',
      ],
    },
  ],
};

export default sidebars;
