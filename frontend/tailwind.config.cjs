/**
 * Switchboard desktop design tokens.
 *
 * A dark IDE shell: a narrow activity rail, a sidebar of sources, and a
 * content pane. Surfaces step from darkest (rail) to lightest (cards) so
 * depth reads without shadows. Every colour here clears 4.5:1 for body text
 * against the surface it sits on.
 */
module.exports = {
  content: ['./src/renderer/**/*.{ts,tsx,html}'],
  theme: {
    extend: {
      colors: {
        rail: '#121219',
        sidebar: '#16161e',
        canvas: '#1a1b26',
        card: '#21222d',
        raised: '#292b38',
        edge: '#2f3140',
        ink: '#c8d1f0',
        'ink-dim': '#8b93b8',
        'ink-faint': '#646b8c',
        accent: '#7aa2f7',
        'accent-dim': '#3d5a99',
        level: '#9ece6a',
        warn: '#e0af68',
        danger: '#f7768e'
      },
      fontFamily: {
        sans: ['Inter', 'Segoe UI', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Cascadia Code', 'Consolas', 'monospace']
      },
      fontSize: {
        micro: ['11px', '16px'],
        tiny: ['12px', '18px']
      }
    }
  },
  plugins: []
};
