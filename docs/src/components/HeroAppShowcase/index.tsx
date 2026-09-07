import React, { useState } from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Link from '@docusaurus/Link';
import styles from './styles.module.css';

interface FeatureTab {
  id: string;
  label: string;
  icon: string;
  desktopImage: string;
  desktopAlt: string;
  desktopBadge: string;
  androidImage: string;
  androidAlt: string;
  androidBadge: string;
  docPath: string;
  description: string;
}

const TABS: FeatureTab[] = [
  {
    id: 'displays',
    label: 'Displays (DDC/CI)',
    icon: '🖥️',
    desktopImage: 'img/screenshot_desktop_displays.png',
    desktopAlt: 'Switchboard Desktop Displays & DDC/CI Hardware Controls',
    desktopBadge: 'Desktop: Multi-Monitor · VESA MCCS · DXVA2 API',
    androidImage: 'img/screenshot_android_displays.png',
    androidAlt: 'Switchboard Android Display Brightness Sliders',
    androidBadge: 'Android: M3 Expressive · Real-time Feedback',
    docPath: '/displays-ddcci',
    description: 'Direct hardware brightness and contrast control for external and internal panels via VESA DDC/CI and WMI.',
  },
  {
    id: 'audio',
    label: 'Audio & Media',
    icon: '🎛️',
    desktopImage: 'img/screenshot_desktop_audio.png',
    desktopAlt: 'Switchboard Desktop Audio Session Mixer',
    desktopBadge: 'Desktop: WASAPI Core Audio · Per-App Sliders · SMTC',
    androidImage: 'img/screenshot_android_audio.png',
    androidAlt: 'Switchboard Android Volume & Media Player',
    androidBadge: 'Android: Album Art Extraction · App Volume Control',
    docPath: '/audio-mixer',
    description: 'Fine-grained Windows Core Audio session mixer, master volume, and rich SMTC playback controls.',
  },
  {
    id: 'files',
    label: 'File Transfer',
    icon: '📁',
    desktopImage: 'img/screenshot_desktop_files.png',
    desktopAlt: 'Switchboard Desktop Encrypted File Transfer Panel',
    desktopBadge: 'Desktop: Drag-and-Drop · Live Transfer Progress',
    androidImage: 'img/screenshot_android_files.png',
    androidAlt: 'Switchboard Android File Transfer Screen',
    androidBadge: 'Android: SAF Picker · SHA-256 Verified Streaming',
    docPath: '/file-transfer',
    description: 'Zero-cloud peer-to-peer chunked file streaming over local encrypted WebSocket with SHA-256 verification.',
  },
  {
    id: 'devices',
    label: 'Zero-Trust Pairing',
    icon: '🔒',
    desktopImage: 'img/screenshot_desktop_devices.png',
    desktopAlt: 'Switchboard Desktop Pairing QR Code and Devices',
    desktopBadge: 'Desktop: Ephemeral QR · Session Revocation',
    androidImage: 'img/screenshot_android_pairing.png',
    androidAlt: 'Switchboard Android Connection Details & Host Sheet',
    androidBadge: 'Android: CameraX QR Scanner · ECDH Key Exchange',
    docPath: '/security-pairing',
    description: 'Passwordless cryptographic pairing via ephemeral X25519 elliptic-curve Diffie-Hellman and AEAD encryption.',
  },
  {
    id: 'settings',
    label: 'Settings',
    icon: '⚙️',
    desktopImage: 'img/screenshot_desktop_settings.png',
    desktopAlt: 'Switchboard Desktop Configuration Panel',
    desktopBadge: 'Desktop: Save Directory · Transfer Rate Units · Tray',
    androidImage: 'img/screenshot_android_settings.png',
    androidAlt: 'Switchboard Android Settings & Appearance',
    androidBadge: 'Android: Material You Dynamic Wallpaper Theming',
    docPath: '/getting-started',
    description: 'Customizable transfer rate units, download destination folders, system tray behaviors, and theme engine.',
  },
];

export default function HeroAppShowcase(): React.ReactElement {
  const [activeTabId, setActiveTabId] = useState<string>('displays');
  const [hoveredTarget, setHoveredTarget] = useState<'desktop' | 'android' | null>(null);
  const [deviceView, setDeviceView] = useState<'all' | 'android' | 'desktop'>('all');

  const activeTab = TABS.find((t) => t.id === activeTabId) || TABS[0];
  const desktopSrc = useBaseUrl(activeTab.desktopImage);
  const androidSrc = useBaseUrl(activeTab.androidImage);

  return (
    <div className={styles.showcaseRoot}>
      <div className={styles.ambientGlow} />

      <div className={styles.tabsHeader}>
        <div className={styles.tabList} role="tablist" aria-label="Feature showcase switcher">
          {TABS.map((tab) => {
            const isActive = tab.id === activeTabId;
            return (
              <button
                key={tab.id}
                type="button"
                role="tab"
                aria-selected={isActive}
                onClick={() => setActiveTabId(tab.id)}
                className={`${styles.tabButton} ${isActive ? styles.tabButtonActive : ''}`}
              >
                <span className={styles.tabIcon}>{tab.icon}</span>
                <span>{tab.label}</span>
              </button>
            );
          })}
        </div>
      </div>

      <p className={styles.tabDescription}>
        {activeTab.description}{' '}
        <Link to={activeTab.docPath} className={styles.tabLearnMore}>
          Learn more →
        </Link>
      </p>

      <div className={styles.mobileDeviceSwitcher} role="group">
        <button
          type="button"
          onClick={() => setDeviceView('android')}
          className={`${styles.mobileSwitchBtn} ${deviceView === 'android' ? styles.mobileSwitchBtnActive : ''}`}
        >
          📱 Android
        </button>
        <button
          type="button"
          onClick={() => setDeviceView('desktop')}
          className={`${styles.mobileSwitchBtn} ${deviceView === 'desktop' ? styles.mobileSwitchBtnActive : ''}`}
        >
          🖥️ Desktop
        </button>
        <button
          type="button"
          onClick={() => setDeviceView('all')}
          className={`${styles.mobileSwitchBtn} ${deviceView === 'all' ? styles.mobileSwitchBtnActive : ''}`}
        >
          📱+🖥️ Both
        </button>
      </div>

      <div className={styles.stageContainer}>
        {/* Desktop Mockup */}
        <div
          className={`${styles.desktopWindow} ${
            deviceView === 'android' ? styles.hideOnMobile : ''
          } ${hoveredTarget === 'desktop' ? styles.desktopWindowFocused : ''}`}
          onMouseEnter={() => setHoveredTarget('desktop')}
          onMouseLeave={() => setHoveredTarget(null)}
        >
          <div className={styles.windowTitlebar}>
            <div className={styles.trafficLights}>
              <span className={styles.dotClose} />
              <span className={styles.dotMinimize} />
              <span className={styles.dotMaximize} />
            </div>
            <div className={styles.windowTitle}>
              <span>Switchboard Host (Windows Daemon)</span>
            </div>
            <div className={styles.platformBadge}>
              <span>🖥️ Desktop</span>
            </div>
          </div>

          <div className={styles.desktopScreen}>
            <img
              src={desktopSrc}
              alt={activeTab.desktopAlt}
              className={styles.desktopImage}
              loading="eager"
            />
            <div className={styles.desktopFloatingTag}>{activeTab.desktopBadge}</div>
          </div>
        </div>

        {/* Android Phone Mockup */}
        <div
          className={`${styles.phoneMockup} ${
            deviceView === 'desktop' ? styles.hideOnMobile : ''
          } ${hoveredTarget === 'android' ? styles.phoneMockupFocused : ''}`}
          onMouseEnter={() => setHoveredTarget('android')}
          onMouseLeave={() => setHoveredTarget(null)}
        >
          {/* Floating Mobile Badge */}
          <div className={styles.phoneFloatingBadge}>
            <span className={styles.phoneBadgeText}>📱 Android (Compose)</span>
          </div>

          <div className={styles.phoneHardwareTop}>
            <div className={styles.phoneSpeaker} />
            <div className={styles.phoneCamera} />
          </div>

          <div className={styles.phoneScreen}>
            <img
              src={androidSrc}
              alt={activeTab.androidAlt}
              className={styles.phoneImage}
              loading="eager"
            />
          </div>

          <div className={styles.phoneHardwareBottom}>
            <div className={styles.homeIndicator} />
          </div>
        </div>
      </div>
    </div>
  );
}
