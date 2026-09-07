import React from 'react';
import Link from '@docusaurus/Link';
import styles from './styles.module.css';

export interface BadgeItem {
  id: string;
  label: string;
  value: string;
  icon: string;
  variant?: 'blue' | 'green' | 'cyan' | 'purple' | 'yellow';
  link?: string;
  tooltip?: string;
}

const DEFAULT_BADGES: BadgeItem[] = [
  {
    id: 'arch',
    label: 'Architecture',
    value: 'Go + Electron + Compose',
    icon: '⚡',
    variant: 'blue',
    link: '/architecture',
    tooltip: 'Monorepo daemon host and native Jetpack Compose mobile client',
  },
  {
    id: 'crypto',
    label: 'Zero-Trust E2EE',
    value: 'X25519 + AES-256-GCM',
    icon: '🔒',
    variant: 'cyan',
    link: '/security-pairing',
    tooltip: 'Zero-password key exchange over ephemeral QR payload and encrypted WebSockets',
  },
  {
    id: 'hardware',
    label: 'Hardware Control',
    value: 'VESA DDC/CI & WMI',
    icon: '🖥️',
    variant: 'purple',
    link: '/displays-ddcci',
    tooltip: 'Hardware brightness and contrast control for multi-monitor rigs',
  },
  {
    id: 'audio',
    label: 'Audio Subsystem',
    value: 'WASAPI Session Mixer',
    icon: '🎛️',
    variant: 'green',
    link: '/audio-mixer',
    tooltip: 'Windows Core Audio per-process volume mixing and SMTC transport sync',
  },
  {
    id: 'files',
    label: 'File Transfer',
    value: 'Chunked P2P + SAF',
    icon: '📁',
    variant: 'yellow',
    link: '/file-transfer',
    tooltip: 'Encrypted chunk streaming with SHA-256 verification and Android SAF integration',
  },
];

interface Props {
  badges?: BadgeItem[];
  className?: string;
}

export default function EngineeringBadges({
  badges = DEFAULT_BADGES,
  className = '',
}: Props): React.ReactElement {
  return (
    <div className={`${styles.badgeStrip} ${className}`} role="list">
      {badges.map((b) => {
        const content = (
          <div className={`${styles.badgeCard} ${styles['variant_' + (b.variant || 'blue')]}`}>
            <span className={styles.badgeIcon}>{b.icon}</span>
            <div className={styles.badgeText}>
              <span className={styles.badgeLabel}>{b.label}</span>
              <span className={styles.badgeValue}>{b.value}</span>
            </div>
          </div>
        );

        if (b.link) {
          return (
            <Link
              key={b.id}
              to={b.link}
              className={styles.badgeLink}
              title={b.tooltip || `${b.label}: ${b.value}`}
            >
              {content}
            </Link>
          );
        }

        return (
          <div
            key={b.id}
            className={styles.badgeLink}
            title={b.tooltip || `${b.label}: ${b.value}`}
          >
            {content}
          </div>
        );
      })}
    </div>
  );
}
