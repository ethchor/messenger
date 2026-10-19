//! Web Push endpoints come from clients, and the server then sends requests
//! to them. So the server only talks to public https addresses there,
//! never to its own network (unless `allow_private_endpoints` is set, for
//! a push service on the LAN). The check also runs on every DNS lookup, so
//! a name that later resolves to an inside address doesn't get through.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use reqwest::{
    dns::{Addrs, Name, Resolve, Resolving},
    Url,
};

/// Longest endpoint URL accepted.
const MAX_ENDPOINT_LEN: usize = 1024;

/// Checks an endpoint a client registered. Host names are checked again
/// when they are looked up.
pub fn check(endpoint: &str, allow_private: bool) -> Result<Url, String> {
    if endpoint.len() > MAX_ENDPOINT_LEN {
        return Err("push endpoint too long".into());
    }
    let url = Url::parse(endpoint).map_err(|_| "push endpoint is not a URL".to_string())?;
    match url.scheme() {
        "https" => {}
        "http" if allow_private => {}
        _ => return Err("push endpoint must use https".into()),
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err("push endpoint must not carry credentials".into());
    }
    let ip = match url.host() {
        None => return Err("push endpoint has no host".into()),
        Some(url::Host::Domain(_)) => None,
        Some(url::Host::Ipv4(ip)) => Some(IpAddr::V4(ip)),
        Some(url::Host::Ipv6(ip)) => Some(IpAddr::V6(ip)),
    };
    if let Some(ip) = ip {
        if !allow_private && !is_public(ip) {
            return Err("push endpoint must be a public address".into());
        }
    }
    Ok(url)
}

/// Whether an address is on the public internet.
pub fn is_public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => is_public_v4(ip),
        IpAddr::V6(ip) => is_public_v6(ip),
    }
}

fn is_public_v4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_broadcast()
        || ip.is_documentation()
        || ip.is_multicast()
        || a == 0 // "this network"
        || (a == 100 && (64..128).contains(&b)) // carrier-grade NAT
        || (a == 192 && b == 0 && c == 0) // IETF protocol assignments
        || (a == 198 && (18..20).contains(&b)) // benchmarking
        || a >= 240) // reserved
}

fn is_public_v6(ip: Ipv6Addr) -> bool {
    if let Some(v4) = ip.to_ipv4_mapped() {
        return is_public_v4(v4);
    }
    let segments = ip.segments();
    // NAT64 (64:ff9b::/96) reaches IPv4 addresses: judge the embedded one.
    if segments[..6] == [0x64, 0xff9b, 0, 0, 0, 0] {
        let v4 =
            Ipv4Addr::new((segments[6] >> 8) as u8, segments[6] as u8, (segments[7] >> 8) as u8, segments[7] as u8);
        return is_public_v4(v4);
    }
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_multicast()
        || (segments[0] & 0xfe00) == 0xfc00 // unique local
        || (segments[0] & 0xffc0) == 0xfe80 // link local
        || (segments[0] == 0x2001 && segments[1] == 0x0db8)) // documentation
}

/// DNS lookups for Web Push that drop non-public addresses.
#[derive(Debug, Clone, Copy)]
pub struct PublicOnlyResolver;

impl Resolve for PublicOnlyResolver {
    fn resolve(&self, name: Name) -> Resolving {
        Box::pin(async move {
            let host = name.as_str().to_string();
            let addrs: Vec<SocketAddr> = tokio::net::lookup_host((host.as_str(), 0)).await?.collect();
            let public: Vec<SocketAddr> = addrs.into_iter().filter(|a| is_public(a.ip())).collect();
            if public.is_empty() {
                return Err(format!("{host} has no public address").into());
            }
            let addrs: Addrs = Box::new(public.into_iter());
            Ok(addrs)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_public_https_endpoints() {
        assert!(check("https://ntfy.sh/upABC?up=1", false).is_ok());
        assert!(check("https://203.0.114.1/up", false).is_ok());
        for bad in [
            "http://ntfy.sh/up",
            "ftp://ntfy.sh/up",
            "https://127.0.0.1/up",
            "https://10.1.2.3/up",
            "https://192.168.1.20:8080/up",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/up",
            "https://[fd00::1]/up",
            "https://[::ffff:127.0.0.1]/up",
            "https://user:pw@ntfy.sh/up",
            "not a url",
        ] {
            assert!(check(bad, false).is_err(), "{bad} should be refused");
        }
        assert!(check("http://192.168.1.20:8080/up", true).is_ok());
    }

    #[test]
    fn address_ranges() {
        for public in ["8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "64:ff9b::808:808"] {
            assert!(is_public(public.parse().unwrap()), "{public}");
        }
        for private in [
            "0.0.0.0",
            "100.64.0.1",
            "172.16.0.1",
            "192.0.0.8",
            "198.18.0.1",
            "224.0.0.1",
            "255.255.255.255",
            "fe80::1",
            "64:ff9b::a00:1",
            "2001:db8::1",
        ] {
            assert!(!is_public(private.parse().unwrap()), "{private}");
        }
    }
}
