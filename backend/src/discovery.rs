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

/// `aa:bb:cc:dd:ee:ff`, lower case, and not the all-zero address.
pub fn clean_mac(s: &str) -> Option<String> {
    let s = s.trim().to_ascii_lowercase();
    let parts: Vec<&str> = s.split([':', '-']).collect();
    if parts.len() != 6 || parts.iter().any(|p| p.len() != 2 || !p.bytes().all(|b| b.is_ascii_hexdigit())) || parts.iter().all(|p| *p == "00") {
        return None;
    }
    Some(parts.join(":"))
}

/// Hardware addresses of the network cards behind `lan_addresses`, for Wake-on-LAN.
#[cfg(target_os = "linux")]
pub fn lan_macs() -> Vec<String> {
    let mut out: Vec<String> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback() && matches!(i.ip(), IpAddr::V4(a) if !a.is_link_local()))
        .filter(|i| !["docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "tailscale", "zt"].iter().any(|p| i.name.starts_with(p)))
        .filter_map(|i| clean_mac(&std::fs::read_to_string(format!("/sys/class/net/{}/address", i.name)).ok()?))
        .collect();
    out.sort();
    out.dedup();
    out
}

#[cfg(windows)]
pub fn lan_macs() -> Vec<String> {
    // The main network card; enough to wake the machine.
    mac_address::get_mac_address().ok().flatten().and_then(|m| clean_mac(&m.to_string())).into_iter().collect()
}

#[cfg(not(any(target_os = "linux", windows)))]
pub fn lan_macs() -> Vec<String> {
    Vec::new()
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mac_addresses_are_cleaned() {
        assert_eq!(clean_mac("AA:BB:CC:DD:EE:0F\n").as_deref(), Some("aa:bb:cc:dd:ee:0f"));
        assert_eq!(clean_mac("aa-bb-cc-dd-ee-ff").as_deref(), Some("aa:bb:cc:dd:ee:ff"));
        assert_eq!(clean_mac("00:00:00:00:00:00"), None);
        assert_eq!(clean_mac("aa:bb:cc"), None);
        assert_eq!(clean_mac("zz:bb:cc:dd:ee:ff"), None);
    }
}
