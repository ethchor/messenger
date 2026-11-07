//! Files in an S3-compatible bucket: Cloudflare R2, Backblaze B2, MinIO,
//! AWS S3 and the like.
//!
//! The server never carries the bytes. It signs short-lived links (AWS
//! Signature Version 4, as query parameters) that let a phone upload one
//! file of an exact size and SHA-256 straight to the bucket, or fetch one.
//! Its own calls to the bucket (checking an upload, deleting) are signed the
//! same way, in headers.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine as _;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use url::Url;

/// Where the bucket is and how to sign for it.
#[derive(Debug, Clone)]
pub struct S3Config {
    /// The service's address, without the bucket: for R2,
    /// `https://<account id>.r2.cloudflarestorage.com`.
    pub endpoint: String,
    pub bucket: String,
    /// `auto` for R2; the bucket's region elsewhere.
    pub region: String,
    pub access_key: String,
    pub secret_key: String,
}

/// What the bucket says about a stored file.
pub(crate) struct ObjectInfo {
    pub size: u64,
    /// SHA-256, when the bucket kept the checksum it was sent.
    pub sha256: Option<Vec<u8>>,
}

pub(crate) struct S3 {
    config: S3Config,
    endpoint: Url,
    http: reqwest::Client,
}

const UNSIGNED: &str = "UNSIGNED-PAYLOAD";

impl S3 {
    pub(crate) fn new(config: S3Config) -> anyhow::Result<Self> {
        let endpoint = Url::parse(config.endpoint.trim_end_matches('/'))
            .map_err(|e| anyhow::anyhow!("the S3 endpoint {:?} isn't a URL: {e}", config.endpoint))?;
        if endpoint.host_str().is_none() {
            anyhow::bail!("the S3 endpoint {:?} has no host", config.endpoint);
        }
        if config.bucket.is_empty() || config.access_key.is_empty() || config.secret_key.is_empty() {
            anyhow::bail!("S3 storage needs a bucket, an access key and a secret key");
        }
        // reqwest brings rustls without a crypto provider; use ring, like the
        // rest of the server. Fails harmlessly if one is already installed.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(30))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|e| anyhow::anyhow!("S3 client: {e}"))?;
        Ok(Self { config, endpoint, http })
    }

    /// A link to upload exactly `size` bytes with this SHA-256 under `key`,
    /// and the headers the upload has to carry.
    pub(crate) fn upload_link(
        &self,
        key: &str,
        size: u64,
        sha256: &[u8],
        valid: Duration,
    ) -> (String, Vec<(String, String)>) {
        let checksum = base64::engine::general_purpose::STANDARD.encode(sha256);
        let headers =
            vec![("content-length".to_string(), size.to_string()), ("x-amz-checksum-sha256".to_string(), checksum)];
        let url = self.presign("PUT", key, &headers, valid, SystemTime::now());
        // The phone's HTTP library sets Content-Length itself.
        (url, headers.into_iter().filter(|(name, _)| name != "content-length").collect())
    }

    /// A link to download `key`.
    pub(crate) fn download_link(&self, key: &str, valid: Duration) -> String {
        self.presign("GET", key, &[], valid, SystemTime::now())
    }

    /// The stored file's size and checksum, or `None` if there's no such file.
    pub(crate) async fn head(&self, key: &str) -> anyhow::Result<Option<ObjectInfo>> {
        let response = self.send(reqwest::Method::HEAD, key, &[("x-amz-checksum-mode", "ENABLED")]).await?;
        match response.status().as_u16() {
            200 => {}
            404 => return Ok(None),
            status => anyhow::bail!("the bucket answered {status} when checking a file"),
        }
        let headers = response.headers();
        let size = headers
            .get(reqwest::header::CONTENT_LENGTH)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse().ok())
            .ok_or_else(|| anyhow::anyhow!("the bucket didn't say how big the file is"))?;
        let sha256 = headers
            .get("x-amz-checksum-sha256")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| base64::engine::general_purpose::STANDARD.decode(v).ok());
        Ok(Some(ObjectInfo { size, sha256 }))
    }

    /// Deletes `key`; a missing file is fine.
    pub(crate) async fn delete(&self, key: &str) -> anyhow::Result<()> {
        let response = self.send(reqwest::Method::DELETE, key, &[]).await?;
        match response.status().as_u16() {
            200..=299 | 404 => Ok(()),
            status => anyhow::bail!("the bucket answered {status} when deleting a file"),
        }
    }

    async fn send(
        &self,
        method: reqwest::Method,
        key: &str,
        extra: &[(&str, &str)],
    ) -> anyhow::Result<reqwest::Response> {
        let now = SystemTime::now();
        let (date, stamp) = timestamps(now);
        let mut headers: Vec<(String, String)> = vec![
            ("host".into(), self.host()),
            ("x-amz-content-sha256".into(), UNSIGNED.into()),
            ("x-amz-date".into(), stamp.clone()),
        ];
        headers.extend(extra.iter().map(|(n, v)| (n.to_string(), v.to_string())));
        headers.sort();
        let signed = signed_header_names(&headers);
        let path = self.path(key);
        let canonical = canonical_request(method.as_str(), &path, "", &headers, UNSIGNED);
        let scope = self.scope(&date);
        let signature = self.signature(&date, &stamp, &scope, &canonical);
        let authorization = format!(
            "AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed}, Signature={signature}",
            self.config.access_key
        );
        let mut request = self.http.request(method, self.url(&path, ""));
        for (name, value) in headers.iter().filter(|(n, _)| n != "host") {
            request = request.header(name, value);
        }
        Ok(request.header("authorization", authorization).send().await?)
    }

    fn presign(
        &self,
        method: &str,
        key: &str,
        headers: &[(String, String)],
        valid: Duration,
        now: SystemTime,
    ) -> String {
        let (date, stamp) = timestamps(now);
        let mut headers: Vec<(String, String)> = headers.to_vec();
        headers.push(("host".into(), self.host()));
        headers.sort();
        let signed = signed_header_names(&headers);
        let scope = self.scope(&date);
        let mut query = [
            ("X-Amz-Algorithm".to_string(), "AWS4-HMAC-SHA256".to_string()),
            ("X-Amz-Credential".to_string(), format!("{}/{scope}", self.config.access_key)),
            ("X-Amz-Date".to_string(), stamp.clone()),
            ("X-Amz-Expires".to_string(), valid.as_secs().clamp(1, 7 * 24 * 3600).to_string()),
            ("X-Amz-SignedHeaders".to_string(), signed),
        ];
        query.sort();
        let query = query.iter().map(|(k, v)| format!("{}={}", encode(k), encode(v))).collect::<Vec<_>>().join("&");
        let path = self.path(key);
        let canonical = canonical_request(method, &path, &query, &headers, UNSIGNED);
        let signature = self.signature(&date, &stamp, &scope, &canonical);
        self.url(&path, &format!("{query}&X-Amz-Signature={signature}"))
    }

    /// Path-style addressing: `/<bucket>/<key>`, which every service takes.
    fn path(&self, key: &str) -> String {
        let base = self.endpoint.path().trim_end_matches('/');
        let segments = std::iter::once(self.config.bucket.as_str()).chain(key.split('/'));
        format!("{base}/{}", segments.map(encode).collect::<Vec<_>>().join("/"))
    }

    fn url(&self, path: &str, query: &str) -> String {
        let origin = self.endpoint.origin().ascii_serialization();
        if query.is_empty() {
            format!("{origin}{path}")
        } else {
            format!("{origin}{path}?{query}")
        }
    }

    fn host(&self) -> String {
        let host = self.endpoint.host_str().unwrap_or_default();
        match self.endpoint.port() {
            Some(port) => format!("{host}:{port}"),
            None => host.to_string(),
        }
    }

    fn scope(&self, date: &str) -> String {
        format!("{date}/{}/s3/aws4_request", self.config.region)
    }

    fn signature(&self, date: &str, stamp: &str, scope: &str, canonical: &str) -> String {
        let to_sign = format!("AWS4-HMAC-SHA256\n{stamp}\n{scope}\n{}", hex(&Sha256::digest(canonical.as_bytes())));
        let mut key = hmac(format!("AWS4{}", self.config.secret_key).as_bytes(), date.as_bytes());
        for part in [self.config.region.as_str(), "s3", "aws4_request"] {
            key = hmac(&key, part.as_bytes());
        }
        hex(&hmac(&key, to_sign.as_bytes()))
    }
}

fn canonical_request(method: &str, path: &str, query: &str, headers: &[(String, String)], payload: &str) -> String {
    let lines: String = headers.iter().map(|(n, v)| format!("{n}:{}\n", v.trim())).collect();
    format!("{method}\n{path}\n{query}\n{lines}\n{}\n{payload}", signed_header_names(headers))
}

fn signed_header_names(sorted: &[(String, String)]) -> String {
    sorted.iter().map(|(n, _)| n.as_str()).collect::<Vec<_>>().join(";")
}

fn hmac(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).expect("HMAC takes any key");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// URI-encodes everything but the unreserved characters, as SigV4 wants.
fn encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~') {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

/// `YYYYMMDD` and `YYYYMMDDTHHMMSSZ`, in UTC.
fn timestamps(now: SystemTime) -> (String, String) {
    let secs = now.duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
    let (days, rest) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    // Days to a civil date (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    let date = format!("{year:04}{month:02}{day:02}");
    let stamp = format!("{date}T{:02}{:02}{:02}Z", rest / 3600, rest % 3600 / 60, rest % 60);
    (date, stamp)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn aws_example() -> S3 {
        S3::new(S3Config {
            endpoint: "https://s3.amazonaws.com".into(),
            bucket: "examplebucket".into(),
            region: "us-east-1".into(),
            access_key: "AKIAIOSFODNN7EXAMPLE".into(),
            secret_key: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".into(),
        })
        .unwrap()
    }

    #[test]
    fn dates_are_utc() {
        // 2013-05-24T00:00:00Z
        assert_eq!(
            timestamps(UNIX_EPOCH + Duration::from_secs(1_369_353_600)),
            ("20130524".into(), "20130524T000000Z".into())
        );
        assert_eq!(timestamps(UNIX_EPOCH + Duration::from_secs(951_782_400 + 3661)).1, "20000229T010101Z");
    }

    /// The presigned GET from the AWS documentation ("Authenticating
    /// Requests: Using Query Parameters"), here with path-style addressing,
    /// so only the path differs from the documented canonical request.
    #[test]
    fn signs_like_the_aws_example() {
        let s3 = aws_example();
        let headers = vec![("host".to_string(), "s3.amazonaws.com".to_string())];
        let query = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host";
        let canonical = canonical_request("GET", "/examplebucket/test.txt", query, &headers, UNSIGNED);
        let scope = s3.scope("20130524");
        assert_eq!(scope, "20130524/us-east-1/s3/aws4_request");
        // The virtual-hosted request of the documentation signs to this.
        let documented = canonical_request(
            "GET",
            "/test.txt",
            query,
            &[("host".to_string(), "examplebucket.s3.amazonaws.com".to_string())],
            UNSIGNED,
        );
        assert_eq!(
            s3.signature("20130524", "20130524T000000Z", &scope, &documented),
            "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
        );
        let link = s3.presign(
            "GET",
            "test.txt",
            &[],
            Duration::from_secs(86_400),
            UNIX_EPOCH + Duration::from_secs(1_369_353_600),
        );
        assert!(link.starts_with("https://s3.amazonaws.com/examplebucket/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256&"));
        assert!(link.ends_with(&format!(
            "&X-Amz-Signature={}",
            s3.signature("20130524", "20130524T000000Z", &scope, &canonical)
        )));
    }

    #[test]
    fn uploads_sign_their_size_and_checksum() {
        let s3 = aws_example();
        let (link, headers) = s3.upload_link("blobs/ab", 42, &[7; 32], Duration::from_secs(3600));
        assert!(link.contains("X-Amz-SignedHeaders=content-length%3Bhost%3Bx-amz-checksum-sha256"));
        assert_eq!(
            headers,
            vec![("x-amz-checksum-sha256".to_string(), base64::engine::general_purpose::STANDARD.encode([7u8; 32]))]
        );
    }

    /// Against a real bucket (`MESSENGER_TEST_S3=endpoint,bucket,key,secret`):
    /// an upload link takes only the file it was made for.
    #[tokio::test]
    async fn a_bucket_refuses_other_files() {
        let Ok(spec) = std::env::var("MESSENGER_TEST_S3") else { return };
        let p: Vec<&str> = spec.split(',').collect();
        let s3 = S3::new(S3Config {
            endpoint: p[0].into(),
            bucket: p[1].into(),
            region: "us-east-1".into(),
            access_key: p[2].into(),
            secret_key: p[3].into(),
        })
        .unwrap();
        let body = b"the real file".to_vec();
        let (url, headers) =
            s3.upload_link("blobs/test", body.len() as u64, &Sha256::digest(&body), Duration::from_secs(60));
        let put = |bytes: Vec<u8>| {
            let mut request = reqwest::Client::new().put(&url).body(bytes);
            for (n, v) in &headers {
                request = request.header(n, v);
            }
            request.send()
        };
        let mut other = body.clone();
        other[0] = b'T';
        assert!(!put(other).await.unwrap().status().is_success(), "same size, other bytes");
        assert!(!put(b"longer than asked".to_vec()).await.unwrap().status().is_success(), "other size");
        assert!(s3.head("blobs/test").await.unwrap().is_none());
        assert!(put(body.clone()).await.unwrap().status().is_success());
        let info = s3.head("blobs/test").await.unwrap().unwrap();
        assert_eq!(info.size, body.len() as u64);
        let fetched = reqwest::get(s3.download_link("blobs/test", Duration::from_secs(60))).await.unwrap();
        assert_eq!(fetched.bytes().await.unwrap().to_vec(), body);
        s3.delete("blobs/test").await.unwrap();
        assert!(s3.head("blobs/test").await.unwrap().is_none());
    }
}
