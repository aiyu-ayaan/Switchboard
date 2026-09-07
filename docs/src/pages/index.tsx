import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import HomepageFeatures from '@site/src/components/HomepageFeatures';
import HomepageSetup from '@site/src/components/HomepageSetup';
import HeroAppShowcase from '@site/src/components/HeroAppShowcase';
import EngineeringBadges from '@site/src/components/EngineeringBadges';

import styles from './index.module.css';

const STACK = [
  'Go Daemon',
  'Electron',
  'React',
  'Tailwind CSS',
  'Kotlin',
  'Jetpack Compose',
  'VESA DDC/CI (DXVA2)',
  'Windows Core Audio (WASAPI)',
  'X25519 ECDH + AES-256-GCM',
  'mDNS / DNS-SD Zero-Conf',
];

function HomepageHeader() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className={styles.heroBanner}>
      <div className={clsx('container', styles.heroInner)}>
        <span className={styles.heroBadge}>Local-First · Zero-Password E2EE · Hardware Bridge</span>
        <h1 className={styles.heroTitle}>{siteConfig.title}</h1>
        <p className={styles.heroSubtitle}>{siteConfig.tagline}</p>

        {/* Hero Interactive App Mockup */}
        <HeroAppShowcase />

        {/* Primary CTA Buttons */}
        <div className={styles.buttons}>
          <Link
            className={clsx('button button--lg', styles.primaryButton)}
            to="/features">
            Explore Features
          </Link>
          <Link
            className="button button--secondary button--lg"
            to="/intro">
            Read the docs
          </Link>
          <Link
            className="button button--secondary button--lg"
            href="https://github.com/aiyu-ayaan/Switchboard/releases">
            Download App
          </Link>
        </div>

        {/* Centered Engineering & Code Health Badges */}
        <div className={styles.heroBadgesWrapper}>
          <EngineeringBadges />
        </div>

        {/* Tech Stack Pills */}
        <div className={styles.stack}>
          {STACK.map((tech) => (
            <span key={tech} className={styles.stackPill}>
              {tech}
            </span>
          ))}
        </div>
      </div>
    </header>
  );
}

function ClosingCta() {
  return (
    <div className={styles.ctaSection}>
      <h2 className={styles.ctaTitle}>System architecture, API protocols, and hardware subsystems</h2>
      <p className={styles.ctaSubtitle}>
        Engineered for instant responsiveness, privacy-first zero-cloud transmission, and physical hardware control.
      </p>
      <div className={styles.buttons}>
        <Link
          className={clsx('button button--lg', styles.primaryButton)}
          to="/architecture">
          Explore Architecture
        </Link>
        <Link className="button button--secondary button--lg" to="/features">
          View All Features
        </Link>
      </div>
    </div>
  );
}

export default function Home(): ReactNode {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title={siteConfig.title}
      description="Ultra-low-latency, zero-password encrypted control bridge between mobile and PC">
      <HomepageHeader />
      <main>
        {/* Core Capabilities */}
        <section className={styles.section}>
          <div className="container">
            <p className={styles.sectionHeading}>Core Capabilities</p>
            <h2 className={styles.sectionTitle}>
              Engineered for Hardware Control, Low Latency & Local Privacy
            </h2>
            <HomepageFeatures />
          </div>
        </section>

        {/* Installation & Setup */}
        <section className={clsx(styles.section, styles.sectionAlt)}>
          <div className="container">
            <p className={styles.sectionHeading}>Installation & Setup</p>
            <h2 className={styles.sectionTitle}>
              Get Started with Switchboard
            </h2>
            <HomepageSetup />
          </div>
        </section>

        <ClosingCta />
      </main>
    </Layout>
  );
}
