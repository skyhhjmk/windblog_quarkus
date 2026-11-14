use core::slice;

// GF(2^8) with primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11d)
const fn build_tables() -> ([u8; 512], [u8; 256]) {
    let mut exp = [0u8; 512];
    let mut log = [0u8; 256];
    let mut x: u16 = 1;
    let mut i: usize = 0;
    while i < 255 {
        exp[i] = x as u8;
        exp[i + 255] = x as u8;
        log[x as usize] = i as u8;
        x <<= 1;
        if (x & 0x100) != 0 {
            x ^= 0x11d;
        }
        i += 1;
    }
    exp[510] = exp[0];
    exp[511] = exp[1];
    (exp, log)
}

const TABLES: ([u8; 512], [u8; 256]) = build_tables();
const GF_EXP: &[u8; 512] = &TABLES.0;
const GF_LOG: &[u8; 256] = &TABLES.1;

#[inline(always)]
fn gf_mul(a: u8, b: u8) -> u8 {
    if a == 0 || b == 0 {
        0
    } else {
        GF_EXP[(GF_LOG[a as usize] as usize) + (GF_LOG[b as usize] as usize)]
    }
}

#[inline(always)]
fn gf_inv(a: u8) -> u8 {
    if a == 0 {
        0
    } else {
        GF_EXP[255 - (GF_LOG[a as usize] as usize)]
    }
}

/// Computes `dst[i] ^= coeff * src[i]` over GF(2^8).
#[inline]
fn gf_mul_add_slice(dst: &mut [u8], src: &[u8], coeff: u8) {
    if coeff == 0 {
        return;
    }
    if coeff == 1 {
        for (d, &s) in dst.iter_mut().zip(src.iter()) {
            *d ^= s;
        }
        return;
    }
    let log_c = GF_LOG[coeff as usize] as usize;
    for (d, &s) in dst.iter_mut().zip(src.iter()) {
        if s != 0 {
            *d ^= GF_EXP[log_c + (GF_LOG[s as usize] as usize)];
        }
    }
}

/// Systematic Cauchy Reed-Solomon matrix entry at `(row, col)` for `k` data shards.
/// For `row < k`, it is the identity row `I_k`.
/// For `row >= k`, `P[row - k, col] = 1 / (row ^ col)`.
#[inline(always)]
fn rs_matrix_entry(row: usize, col: usize, k: usize) -> u8 {
    if row < k {
        if row == col { 1 } else { 0 }
    } else {
        gf_inv((row as u8) ^ (col as u8))
    }
}

/// Inverts a `k x k` matrix over GF(2^8) in-place using Gauss-Jordan elimination.
fn invert_matrix(mat: &mut [u8], k: usize) -> bool {
    let mut inv = vec![0u8; k * k];
    for i in 0..k {
        inv[i * k + i] = 1;
    }

    for col in 0..k {
        let mut pivot_row = col;
        while pivot_row < k && mat[pivot_row * k + col] == 0 {
            pivot_row += 1;
        }
        if pivot_row == k {
            return false;
        }
        if pivot_row != col {
            for c in 0..k {
                mat.swap(col * k + c, pivot_row * k + c);
                inv.swap(col * k + c, pivot_row * k + c);
            }
        }

        let diag = mat[col * k + col];
        if diag != 1 {
            let inv_diag = gf_inv(diag);
            for c in 0..k {
                mat[col * k + c] = gf_mul(mat[col * k + c], inv_diag);
                inv[col * k + c] = gf_mul(inv[col * k + c], inv_diag);
            }
        }

        for r in 0..k {
            if r == col {
                continue;
            }
            let factor = mat[r * k + col];
            if factor != 0 {
                for c in 0..k {
                    mat[r * k + c] ^= gf_mul(factor, mat[col * k + c]);
                    inv[r * k + c] ^= gf_mul(factor, inv[col * k + c]);
                }
            }
        }
    }

    mat.copy_from_slice(&inv);
    true
}

#[no_mangle]
pub extern "C" fn alloc(size: usize) -> *mut u8 {
    let mut buf = Vec::<u8>::with_capacity(size);
    let ptr = buf.as_mut_ptr();
    core::mem::forget(buf);
    ptr
}

#[no_mangle]
pub extern "C" fn dealloc(ptr: *mut u8, size: usize) {
    if ptr.is_null() || size == 0 {
        return;
    }
    unsafe {
        let _ = Vec::from_raw_parts(ptr, 0, size);
    }
}

/// Standard IEEE 802.3 CRC32.
#[no_mangle]
pub extern "C" fn crc32(ptr: *const u8, len: usize) -> u32 {
    if ptr.is_null() || len == 0 {
        return 0;
    }
    let data = unsafe { slice::from_raw_parts(ptr, len) };
    let mut crc: u32 = 0xFFFF_FFFF;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}

/// Encodes `k` data shards into `m` parity shards (each `shard_len` bytes).
/// Returns 0 on success, -1 on invalid arguments.
#[no_mangle]
pub extern "C" fn rs_encode(
    data_ptr: *const u8,
    k: usize,
    m: usize,
    shard_len: usize,
    parity_out_ptr: *mut u8,
) -> i32 {
    if data_ptr.is_null()
        || parity_out_ptr.is_null()
        || k == 0
        || m == 0
        || k + m > 255
        || shard_len == 0
    {
        return -1;
    }

    let data = unsafe { slice::from_raw_parts(data_ptr, k * shard_len) };
    let parity = unsafe { slice::from_raw_parts_mut(parity_out_ptr, m * shard_len) };
    parity.fill(0);

    for p in 0..m {
        let out_shard = &mut parity[p * shard_len..(p + 1) * shard_len];
        for c in 0..k {
            let coeff = rs_matrix_entry(k + p, c, k);
            let in_shard = &data[c * shard_len..(c + 1) * shard_len];
            gf_mul_add_slice(out_shard, in_shard, coeff);
        }
    }
    0
}

/// Reconstructs missing data shards in-place from any `k` present shards out of `k + m`.
/// `shards_ptr` holds `(k + m) * shard_len` bytes; `present_ptr` holds `k + m` flags (1 = present, 0 = missing).
/// Returns number of recovered data shards (>= 0) on success, or -1 if unrecoverable / invalid.
#[no_mangle]
pub extern "C" fn rs_reconstruct(
    shards_ptr: *mut u8,
    present_ptr: *const u8,
    k: usize,
    m: usize,
    shard_len: usize,
) -> i32 {
    if shards_ptr.is_null()
        || present_ptr.is_null()
        || k == 0
        || k + m > 255
        || shard_len == 0
    {
        return -1;
    }

    let n = k + m;
    let present = unsafe { slice::from_raw_parts(present_ptr, n) };

    let mut missing_data = Vec::new();
    for i in 0..k {
        if present[i] == 0 {
            missing_data.push(i);
        }
    }
    if missing_data.is_empty() {
        return 0;
    }

    let mut used = Vec::with_capacity(k);
    for (idx, &flag) in present.iter().enumerate() {
        if flag != 0 {
            used.push(idx);
            if used.len() == k {
                break;
            }
        }
    }
    if used.len() < k {
        return -1;
    }

    let mut submatrix = vec![0u8; k * k];
    for (r, &row_idx) in used.iter().enumerate() {
        for c in 0..k {
            submatrix[r * k + c] = rs_matrix_entry(row_idx, c, k);
        }
    }
    if !invert_matrix(&mut submatrix, k) {
        return -1;
    }

    let shards = unsafe { slice::from_raw_parts_mut(shards_ptr, n * shard_len) };
    let mut recovered_buf = vec![0u8; missing_data.len() * shard_len];

    for (mi, &data_idx) in missing_data.iter().enumerate() {
        let dst = &mut recovered_buf[mi * shard_len..(mi + 1) * shard_len];
        for (j, &src_idx) in used.iter().enumerate() {
            let coeff = submatrix[data_idx * k + j];
            if coeff != 0 {
                let src = &shards[src_idx * shard_len..(src_idx + 1) * shard_len];
                gf_mul_add_slice(dst, src, coeff);
            }
        }
    }

    for (mi, &data_idx) in missing_data.iter().enumerate() {
        let src = &recovered_buf[mi * shard_len..(mi + 1) * shard_len];
        shards[data_idx * shard_len..(data_idx + 1) * shard_len].copy_from_slice(src);
    }

    missing_data.len() as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_crc32_vector() {
        let input = b"123456789";
        assert_eq!(crc32(input.as_ptr(), input.len()), 0xCBF4_3926);
    }

    #[test]
    fn test_rs_encode_and_reconstruct() {
        let k = 8;
        let m = 3;
        let shard_len = 64;

        let mut original = vec![0u8; k * shard_len];
        for (i, b) in original.iter_mut().enumerate() {
            *b = ((i * 37 + 13) & 0xFF) as u8;
        }

        let mut parity = vec![0u8; m * shard_len];
        assert_eq!(
            rs_encode(original.as_ptr(), k, m, shard_len, parity.as_mut_ptr()),
            0
        );

        let mut all_shards = Vec::with_capacity((k + m) * shard_len);
        all_shards.extend_from_slice(&original);
        all_shards.extend_from_slice(&parity);

        // Drop 3 shards (maximum recoverable = m = 3): data 0, data 5, parity 1
        let mut present = vec![1u8; k + m];
        present[0] = 0;
        present[5] = 0;
        present[k + 1] = 0;
        all_shards[0..shard_len].fill(0);
        all_shards[5 * shard_len..6 * shard_len].fill(0xAA);
        all_shards[(k + 1) * shard_len..(k + 2) * shard_len].fill(0x55);

        let recovered = rs_reconstruct(
            all_shards.as_mut_ptr(),
            present.as_ptr(),
            k,
            m,
            shard_len,
        );
        assert_eq!(recovered, 2);
        assert_eq!(&all_shards[0..k * shard_len], original.as_slice());

        // Drop 4 shards (> m): unrecoverable
        present[2] = 0;
        assert_eq!(
            rs_reconstruct(
                all_shards.as_mut_ptr(),
                present.as_ptr(),
                k,
                m,
                shard_len,
            ),
            -1
        );
    }
}
