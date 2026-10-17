//! Length hiding for message content.
//!
//! Content is framed as `u32 length || content || zero bytes` and padded to the
//! Padmé length of the frame (at least [`MIN_FRAME_LEN`]). Padmé leaks
//! O(log log n) bits of the length and wastes at most about 12% of space.
//! MLS then encrypts the padded frame.

/// Smallest frame, so very short messages all look the same.
pub const MIN_FRAME_LEN: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PaddingError {
    #[error("frame shorter than its length prefix")]
    Truncated,
    #[error("length prefix points past the end of the frame")]
    BadLength,
    #[error("padding bytes are not zero")]
    NonZeroPadding,
}

/// Padmé: rounds `len` up so that only the top bits of its binary
/// representation can be non-zero.
pub fn padme(len: usize) -> usize {
    if len < 2 {
        return len;
    }
    // e = floor(log2(len)), s = floor(log2(e)) + 1
    let e = (usize::BITS - 1 - len.leading_zeros()) as usize;
    let s = (usize::BITS - e.leading_zeros()) as usize;
    let last_bits = e.saturating_sub(s);
    let mask = (1usize << last_bits) - 1;
    (len + mask) & !mask
}

/// Frames and pads `content`.
pub fn pad(content: &[u8]) -> Vec<u8> {
    let frame_len = 4 + content.len();
    let padded_len = padme(frame_len).max(MIN_FRAME_LEN);
    let mut out = Vec::with_capacity(padded_len);
    out.extend_from_slice(&(content.len() as u32).to_be_bytes());
    out.extend_from_slice(content);
    out.resize(padded_len, 0);
    out
}

/// Strips the frame and padding, rejecting malformed input.
pub fn unpad(frame: &[u8]) -> Result<&[u8], PaddingError> {
    let prefix: [u8; 4] = frame.get(..4).ok_or(PaddingError::Truncated)?.try_into().unwrap();
    let len = u32::from_be_bytes(prefix) as usize;
    let end = 4usize.checked_add(len).ok_or(PaddingError::BadLength)?;
    if end > frame.len() {
        return Err(PaddingError::BadLength);
    }
    if frame[end..].iter().any(|&b| b != 0) {
        return Err(PaddingError::NonZeroPadding);
    }
    Ok(&frame[4..end])
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn padme_known_values() {
        // Values from the Padmé paper's definition.
        assert_eq!(padme(0), 0);
        assert_eq!(padme(1), 1);
        assert_eq!(padme(9), 10);
        assert_eq!(padme(100), 104);
        assert_eq!(padme(1000), 1024);
        assert_eq!(padme(1025), 1088);
    }

    #[test]
    fn short_messages_share_a_size() {
        assert_eq!(pad(b"").len(), MIN_FRAME_LEN);
        assert_eq!(pad(b"ok").len(), MIN_FRAME_LEN);
        assert_eq!(pad(&[7u8; 59]).len(), MIN_FRAME_LEN);
    }

    proptest! {
        #[test]
        fn round_trip(content in proptest::collection::vec(any::<u8>(), 0..5000)) {
            let framed = pad(&content);
            prop_assert!(framed.len() >= content.len() + 4);
            // Overhead stays within Padmé's bound (12%) plus the minimum frame.
            prop_assert!(framed.len() <= ((content.len() + 4) * 112 / 100).max(MIN_FRAME_LEN) + 1);
            prop_assert_eq!(unpad(&framed).unwrap(), &content[..]);
        }

        #[test]
        fn padme_is_monotonic_and_bounded(len in 2usize..10_000_000) {
            let p = padme(len);
            prop_assert!(p >= len);
            prop_assert!(padme(len + 1) >= p);
            prop_assert!(p as f64 <= len as f64 * 1.13);
        }

        #[test]
        fn unpad_never_panics(frame in proptest::collection::vec(any::<u8>(), 0..300)) {
            let _ = unpad(&frame);
        }
    }
}
