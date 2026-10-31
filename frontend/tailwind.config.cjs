/** Tokens mirror design-system/MASTER.md; values live in src/tokens.css. */
const token = (name) => `rgb(var(--${name}) / <alpha-value>)`
const names = [
  'bg', 'panel', 'container', 'raised', 'highest', 'lowest', 'border', 'border-strong', 'fg', 'muted', 'subtle',
  'primary', 'on-primary', 'primary-container', 'on-primary-container', 'secondary', 'secondary-container', 'on-secondary-container',
  'tertiary', 'on-tertiary', 'tertiary-container', 'on-tertiary-container', 'danger', 'on-danger', 'danger-container',
  'on-danger-container', 'inverse', 'on-inverse', 'on', 'warn',
]

module.exports = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: Object.fromEntries(names.map((n) => [n, token(n)])),
      fontFamily: {
        sans: ['Inter Variable', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Cascadia Code', 'JetBrains Mono', 'monospace'],
      },
      fontSize: {
        caption: ['12px', '16px'],
        label: ['13px', '18px'],
        body: ['14px', '20px'],
        title: ['16px', '22px'],
        headline: ['22px', '28px'],
        page: ['28px', '34px'],
        display: ['44px', '50px'],
      },
      borderRadius: { sm: '8px', DEFAULT: '12px', md: '12px', lg: '16px', xl: '20px', '2xl': '28px', '3xl': '32px' },
      transitionTimingFunction: {
        std: 'cubic-bezier(0.2, 0, 0, 1)',
        decel: 'cubic-bezier(0.05, 0.7, 0.1, 1)',
        accel: 'cubic-bezier(0.3, 0, 0.8, 0.15)',
        // Expressive overshoot, the CSS stand-in for Compose's spatial spring.
        spring: 'cubic-bezier(0.34, 1.36, 0.64, 1)',
      },
      transitionDuration: { fast: '150ms', base: '250ms', slow: '400ms' },
      zIndex: { nav: '30', sheet: '50', toast: '60' },
    },
  },
  plugins: [],
}
