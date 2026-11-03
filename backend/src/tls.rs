//! TLS in front of the phone-facing server (`wss://` and `https://`).
//!
//! It hides the plaintext hello (device name, public keys) from anyone
//! watching the network. It does not authenticate the desktop: the
//! certificate is made fresh at every start and the phone does not check it.
//! The end-to-end handshake with pinned keys does the authenticating, so an
//! active attacker who terminates TLS still cannot read or forge a frame.
//! shortcut: nothing to pin, so no stored certificate; persist one if a
//! client ever pins the certificate instead of trusting the handshake.

use std::sync::Arc;

use anyhow::Result;
use rustls::ServerConfig;
use rustls::crypto::ring::default_provider;
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use tokio_rustls::TlsAcceptor;

pub fn acceptor() -> Result<TlsAcceptor> {
    let made = rcgen::generate_simple_self_signed(vec!["switchboard.local".to_string()])?;
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(made.key_pair.serialize_der()));
    let mut cfg = ServerConfig::builder_with_provider(Arc::new(default_provider()))
        .with_safe_default_protocol_versions()?
        .with_no_client_auth()
        .with_single_cert(vec![made.cert.der().clone()], key)?;
    // WebSocket upgrades are HTTP/1.1 only.
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(TlsAcceptor::from(Arc::new(cfg)))
}

/// What the phone does: accept any certificate. Tests only.
#[cfg(test)]
pub fn trust_all_client() -> Arc<rustls::ClientConfig> {
    use rustls::DigitallySignedStruct;
    use rustls::SignatureScheme;
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerifier};
    use rustls::pki_types::{CertificateDer, ServerName, UnixTime};

    #[derive(Debug)]
    struct AcceptAny;
    impl ServerCertVerifier for AcceptAny {
        fn verify_server_cert(&self, _: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        }
        fn verify_tls12_signature(&self, _: &[u8], _: &CertificateDer<'_>, _: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
            Ok(HandshakeSignatureValid::assertion())
        }
        fn verify_tls13_signature(&self, _: &[u8], _: &CertificateDer<'_>, _: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
            Ok(HandshakeSignatureValid::assertion())
        }
        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            default_provider().signature_verification_algorithms.supported_schemes()
        }
    }

    Arc::new(
        rustls::ClientConfig::builder_with_provider(Arc::new(default_provider()))
            .with_safe_default_protocol_versions()
            .unwrap()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(AcceptAny))
            .with_no_client_auth(),
    )
}
