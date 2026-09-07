import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import styles from './styles.module.css';

type FeatureItem = {
  title: string;
  badge: string;
  image: string;
  link: string;
  description: ReactNode;
};

const FeatureList: FeatureItem[] = [
  {
    title: 'Hardware Displays & DDC/CI',
    badge: 'VESA MCCS & WMI',
    image: 'img/screenshot_desktop_displays.png',
    link: '/displays-ddcci',
    description: (
      <>
        Multi-monitor hardware brightness and contrast control. Communicates directly with physical
        monitors via DXVA2 VESA MCCS and controls laptop displays via WMI.
      </>
    ),
  },
  {
    title: 'Windows Core Audio Mixer & Media',
    badge: 'WASAPI & SMTC',
    image: 'img/screenshot_desktop_audio.png',
    link: '/audio-mixer',
    description: (
      <>
        Independent per-application audio session mixer and master volume control. Live SMTC transport
        sync with album artwork, playback toggle, and track seek.
      </>
    ),
  },
  {
    title: 'Zero-Trust E2EE Pairing',
    badge: 'X25519 + AES-256-GCM',
    image: 'img/screenshot_desktop_devices.png',
    link: '/security-pairing',
    description: (
      <>
        Zero-password ephemeral key exchange generated via QR code. All local network packets are
        authenticated and encrypted with hardware-accelerated AEAD ciphers.
      </>
    ),
  },
  {
    title: 'Encrypted P2P File Transfer',
    badge: 'Chunked Streaming',
    image: 'img/screenshot_desktop_files.png',
    link: '/file-transfer',
    description: (
      <>
        Fast, zero-cloud peer-to-peer file transfer between mobile and computer. Integrated with Android
        Storage Access Framework (SAF) and SHA-256 integrity validation.
      </>
    ),
  },
  {
    title: 'Air Mouse & Remote Touchpad',
    badge: 'Low-Latency Input',
    image: 'img/screenshot_android_main.png',
    link: '/air-mouse',
    description: (
      <>
        Sub-millisecond pointer precision with customizable acceleration curves, multi-finger gestures,
        scroll wheel emulation, and Windows SendInput injection.
      </>
    ),
  },
  {
    title: 'Switchboard Deck Console',
    badge: '8-Key Macro Board',
    image: 'img/screenshot_android_main.png',
    link: '/stream-deck-neo',
    description: (
      <>
        Full hardware console emulation in landscape mode inspired by the Stream Deck Neo. Dynamic LCD
        infobar, pagination, tactile haptics, and custom action routing.
      </>
    ),
  },
];

function Feature({title, badge, image, link, description}: FeatureItem) {
  const imageUrl = useBaseUrl(image);
  return (
    <div className={clsx('col col--4', 'margin-bottom--lg')}>
      <div className={styles.featureCard}>
        <Link to={link} className={styles.featureImageLink} aria-label={title}>
          <div className={styles.featureImageWrapper}>
            <img src={imageUrl} alt={title} className={styles.featureImage} loading="lazy" />
            <span className={styles.featureBadge}>{badge}</span>
          </div>
        </Link>
        <div className={styles.featureContent}>
          <h3 className={styles.featureTitle}>
            <Link to={link} className={styles.featureTitleLink}>
              {title}
            </Link>
          </h3>
          <p className={styles.featureDescription}>{description}</p>
          <div className={styles.featureFooter}>
            <Link to={link} className={styles.featureActionLink}>
              Explore details →
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}

export default function HomepageFeatures(): ReactNode {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
