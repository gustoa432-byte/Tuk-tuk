//! Derive mesh node id from Base64 X.509 DER public key (matches Android NodeIdentity).

use base64::Engine;
use sha2::{Digest, Sha256};

const BASE32: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const ID_BYTES: usize = 10;
const ID_CHARS: usize = 16;

/// `nodeId = Base32(SHA-256(publicKeyDER)[0..10])` — uppercase, no padding.
pub fn derive_node_id(public_key_base64: &str) -> Result<String, String> {
    let raw = public_key_base64.trim();
    if raw.is_empty() {
        return Err("empty_public_key".into());
    }
    let der = base64::engine::general_purpose::STANDARD
        .decode(raw)
        .or_else(|_| base64::engine::general_purpose::STANDARD_NO_PAD.decode(raw))
        .map_err(|e| format!("base64: {e}"))?;
    if der.is_empty() {
        return Err("empty_der".into());
    }
    let digest = Sha256::digest(&der);
    Ok(base32_80(&digest[..ID_BYTES]))
}

fn base32_80(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(ID_CHARS);
    let mut buffer: u32 = 0;
    let mut bits_left: i32 = 0;
    for &b in bytes {
        buffer = (buffer << 8) | u32::from(b);
        bits_left += 8;
        while bits_left >= 5 {
            let index = ((buffer >> (bits_left - 5)) & 0x1F) as usize;
            out.push(BASE32[index] as char);
            bits_left -= 5;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_key_fails() {
        assert!(derive_node_id("").is_err());
        assert!(derive_node_id("   ").is_err());
    }
}
