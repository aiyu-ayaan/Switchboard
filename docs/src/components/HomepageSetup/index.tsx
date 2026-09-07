import {useState, type ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import styles from './styles.module.css';

type TabKey = 'binaries' | 'source' | 'pairing';

interface SetupStep {
  title: string;
  detail: string;
  command?: string;
}

interface TabContent {
  title: string;
  badge: string;
  description: string;
  steps: SetupStep[];
  docsLink: string;
  docsLabel: string;
}

const TAB_DATA: Record<TabKey, TabContent> = {
  binaries: {
    title: 'Install Pre-built Applications',
    badge: 'End Users',
    description:
      'Download packaged release binaries for Windows (Desktop Host + Daemon) and Android APK. Zero compilation required.',
    steps: [
      {
        title: 'Download Desktop Host (Windows)',
        detail:
          'Get the latest Switchboard Setup (.exe) or portable executable from GitHub Releases and launch it on your PC.',
        command: '# Download installer or binary from GitHub Releases:\nhttps://github.com/aiyu-ayaan/Switchboard/releases',
      },
      {
        title: 'Install Mobile App on Android',
        detail:
          'Transfer and install the app-release.apk on your Android phone (Android 9.0 Pie / API 28+ required).',
        command: '# Or install directly via ADB:\nadb install -r switchboard-app.apk',
      },
      {
        title: 'Connect over Local Network',
        detail:
          'Ensure both computer and Android phone are on the same Wi-Fi or LAN subnet. Launch the app and scan the QR code displayed on the desktop.',
      },
    ],
    docsLink: '/getting-started',
    docsLabel: 'View getting started guide',
  },
  source: {
    title: 'Build from Source Monorepo',
    badge: 'Developers',
    description:
      'Fast local inner development loop with Go, pnpm workspaces, Electron, and Gradle.',
    steps: [
      {
        title: 'Clone repository and install dependencies',
        detail: 'Clone the monorepo with submodules, and install frontend dependencies with pnpm:',
        command: 'git clone --recurse-submodules https://github.com/aiyu-ayaan/Switchboard.git\ncd Switchboard\npnpm install',
      },
      {
        title: 'Build and run Go backend daemon',
        detail: 'Build the Go daemon located in backend/ or run via the root runner:',
        command: 'pnpm dev:backend\n# Or run Go directly:\ncd backend && go run ./cmd/switchboard-daemon',
      },
      {
        title: 'Launch Electron desktop frontend',
        detail: 'Start the Vite development server and Electron window with live hot-reload:',
        command: 'pnpm dev:frontend',
      },
      {
        title: 'Build and deploy Android mobile client',
        detail: 'Assemble debug APK with Gradle and deploy directly to attached device or emulator:',
        command: 'pnpm android:build && pnpm android:run',
      },
    ],
    docsLink: '/getting-started',
    docsLabel: 'View full build guide',
  },
  pairing: {
    title: 'Zero-Trust Pairing & Discovery',
    badge: 'Zero Password',
    description:
      'How the cryptographic handshake operates over local wireless networks.',
    steps: [
      {
        title: 'Host Auto-Advertisement (mDNS)',
        detail:
          'The Go daemon advertises a _switchboard._tcp service via mDNS / DNS-SD on the local network subnet.',
      },
      {
        title: 'Ephemeral QR Code Key Exchange',
        detail:
          'The desktop host generates an ephemeral X25519 ECDH keypair and displays the public key and LAN endpoints in a QR code.',
        command: 'switchboard://pair?v=1&host=192.168.1.104:9427&pk=<base64-pubkey>&id=<host-uuid>',
      },
      {
        title: 'Encrypted WebSocket Tunnel',
        detail:
          'The mobile app derives the shared secret, establishes an authenticated AES-256-GCM encrypted tunnel, and saves the paired host in secure storage.',
      },
    ],
    docsLink: '/security-pairing',
    docsLabel: 'View pairing protocol specification',
  },
};

export default function HomepageSetup(): ReactNode {
  const [activeTab, setActiveTab] = useState<TabKey>('binaries');
  const current = TAB_DATA[activeTab];

  return (
    <section className={styles.setupSection}>
      <div className={clsx('container', styles.setupContainer)}>
        <div className={styles.tabList}>
          <button
            type="button"
            className={clsx(
              styles.tabButton,
              activeTab === 'binaries' && styles.tabButtonActive,
            )}
            onClick={() => setActiveTab('binaries')}>
            <span>💻</span> Pre-built Apps
          </button>
          <button
            type="button"
            className={clsx(
              styles.tabButton,
              activeTab === 'source' && styles.tabButtonActive,
            )}
            onClick={() => setActiveTab('source')}>
            <span>⚡</span> Build from Source
          </button>
          <button
            type="button"
            className={clsx(
              styles.tabButton,
              activeTab === 'pairing' && styles.tabButtonActive,
            )}
            onClick={() => setActiveTab('pairing')}>
            <span>🔒</span> Pairing & Discovery
          </button>
        </div>

        <div className={styles.setupCard}>
          <div className={styles.setupHeader}>
            <div className={styles.setupTitle}>
              {current.title}
              <span className="badge badge--secondary">{current.badge}</span>
            </div>
            <p className={styles.setupDesc}>{current.description}</p>
          </div>

          <div className={styles.stepsList}>
            {current.steps.map((step, idx) => (
              <div key={idx} className={styles.stepItem}>
                <div className={styles.stepNumber}>{idx + 1}</div>
                <div className={styles.stepContent}>
                  <div className={styles.stepTitle}>{step.title}</div>
                  <div className={styles.stepDetail}>{step.detail}</div>
                  {step.command && (
                    <pre className={styles.codeSnippet}>
                      <code>{step.command}</code>
                    </pre>
                  )}
                </div>
              </div>
            ))}
          </div>

          <div className={styles.footerLinks}>
            <p className={styles.footerNote}>
              Need more architectural details or advanced configurations?
            </p>
            <Link className={styles.actionLink} to={current.docsLink}>
              {current.docsLabel} &rarr;
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
