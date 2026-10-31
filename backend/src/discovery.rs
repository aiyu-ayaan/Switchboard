//! LAN addresses and mDNS advertising, so a paired phone finds the desktop
//! again after its IP changes, and a phone typing a code can find it at all.

use std::net::IpAddr;

use mdns_sd::{ServiceDaemon, ServiceInfo};
use switchboard_core::protocol::{MDNS_SERVICE, PROTOCOL_VERSION};

/// IPv4 LAN addresses, private ranges first. Virtual bridges (Docker, VMs)
/// are skipped; a phone can never reach them.
pub fn lan_addresses() -> Vec<IpAddr> {
    let mut v: Vec<(u8, IpAddr)> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback())
        .filter(|i| !["docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "tailscale", "zt"].iter().any(|p| i.name.starts_with(p)))
        .filter_map(|i| match i.ip() {
            IpAddr::V4(a) if !a.is_link_local() => Some((if a.is_private() { 0 } else { 1 }, IpAddr::V4(a))),
            _ => None,
        })
        .collect();
    v.sort();
    v.dedup();
    v.into_iter().map(|(_, a)| a).collect()
}

pub struct Advert {
    daemon: ServiceDaemon,
    fullname: Option<String>,
}

impl Advert {
    pub fn new() -> Option<Self> {
        match ServiceDaemon::new() {
            Ok(daemon) => Some(Self { daemon, fullname: None }),
            Err(e) => {
                tracing::warn!("mDNS unavailable: {e}");
                None
            }
        }
    }

    /// (Re-)announces the service; called again after a rename.
    pub fn publish(&mut self, host_id: &str, name: &str, pk: &str, port: u16) {
        if let Some(old) = self.fullname.take() {
            let _ = self.daemon.unregister(&old);
        }
        let addrs = lan_addresses();
        let props = [("id", host_id), ("name", name), ("pk", pk), ("v", &PROTOCOL_VERSION.to_string())];
        let host = format!("switchboard-{}.local.", host_id.to_lowercase().replace(['_', '-'], ""));
        match ServiceInfo::new(MDNS_SERVICE, host_id, &host, &addrs[..], port, &props[..]) {
            Ok(info) => {
                let info = info.enable_addr_auto();
                self.fullname = Some(info.get_fullname().to_owned());
                if let Err(e) = self.daemon.register(info) {
                    tracing::warn!("mDNS register failed: {e}");
                }
            }
            Err(e) => tracing::warn!("mDNS service info: {e}"),
        }
    }
}
