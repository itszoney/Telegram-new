/*
 * security.cpp – Native signing-certificate verification.
 *
 * Called from NativeSecurityBridge.kt via JNI.
 *
 * HOW TO SET THE EXPECTED HASH
 * ─────────────────────────────
 * 1. Sign your release APK with your keystore.
 * 2. Run:
 *      keytool -printcert -jarfile app-release.apk
 *    OR:
 *      apksigner verify --print-certs app-release.apk
 * 3. Copy the SHA-256 fingerprint (as raw bytes, not colon-separated hex)
 *    into EXPECTED_CERT_SHA256 below.
 *
 * Example conversion from "AB:CD:EF:..." hex to bytes:
 *   { 0xAB, 0xCD, 0xEF, ... }
 *
 * IMPORTANT: Replace the placeholder 32 zero-bytes with your real hash
 * before building the release APK.
 */

#include <jni.h>
#include <string.h>
#include <stdint.h>

// ── Expected SHA-256 of the release signing certificate (DER-encoded) ─────
// Replace these 32 bytes with your actual certificate's SHA-256 fingerprint.
// Set all zeros → the check always fails → set your real hash before shipping.
static const uint8_t EXPECTED_CERT_SHA256[32] = {
  // !! REPLACE WITH YOUR RELEASE CERT SHA-256 BYTES !!
  // Run: apksigner verify --print-certs app-release.apk
  //      and convert the hex fingerprint here.
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
};

// ── Minimal SHA-256 implementation (no external dependency needed) ─────────
// We roll our own so this file has zero link-time dependencies beyond libc.

typedef uint32_t u32;
typedef uint64_t u64;
typedef uint8_t  u8;

#define ROR32(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static const u32 K[64] = {
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,
  0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,
  0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,
  0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
  0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,
  0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,
  0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,
  0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
  0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,
  0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,
  0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static void sha256_compress(u32 state[8], const u8 block[64]) {
  u32 w[64], a, b, c, d, e, f, g, h, t1, t2;
  for (int i = 0; i < 16; i++) {
    w[i] = ((u32)block[i*4]     << 24) | ((u32)block[i*4+1] << 16)
         | ((u32)block[i*4+2]   <<  8) | ((u32)block[i*4+3]);
  }
  for (int i = 16; i < 64; i++) {
    u32 s0 = ROR32(w[i-15], 7) ^ ROR32(w[i-15],18) ^ (w[i-15] >> 3);
    u32 s1 = ROR32(w[i- 2],17) ^ ROR32(w[i- 2],19) ^ (w[i- 2]>>10);
    w[i] = w[i-16] + s0 + w[i-7] + s1;
  }
  a = state[0]; b = state[1]; c = state[2]; d = state[3];
  e = state[4]; f = state[5]; g = state[6]; h = state[7];
  for (int i = 0; i < 64; i++) {
    u32 S1  = ROR32(e,6) ^ ROR32(e,11) ^ ROR32(e,25);
    u32 ch  = (e & f) ^ (~e & g);
    t1      = h + S1 + ch + K[i] + w[i];
    u32 S0  = ROR32(a,2) ^ ROR32(a,13) ^ ROR32(a,22);
    u32 maj = (a & b) ^ (a & c) ^ (b & c);
    t2      = S0 + maj;
    h = g; g = f; f = e; e = d + t1;
    d = c; c = b; b = a; a = t1 + t2;
  }
  state[0]+=a; state[1]+=b; state[2]+=c; state[3]+=d;
  state[4]+=e; state[5]+=f; state[6]+=g; state[7]+=h;
}

static void sha256(const u8 *data, size_t len, u8 out[32]) {
  u32 state[8] = {
    0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
    0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19
  };
  u8 block[64];
  size_t processed = 0;

  while (len - processed >= 64) {
    sha256_compress(state, data + processed);
    processed += 64;
  }

  size_t rem = len - processed;
  memcpy(block, data + processed, rem);
  block[rem] = 0x80;
  if (rem < 56) {
    memset(block + rem + 1, 0, 55 - rem);
  } else {
    memset(block + rem + 1, 0, 63 - rem);
    sha256_compress(state, block);
    memset(block, 0, 56);
  }
  u64 bits = (u64)len * 8;
  for (int i = 0; i < 8; i++) block[63 - i] = (u8)(bits >> (i * 8));
  sha256_compress(state, block);

  for (int i = 0; i < 8; i++) {
    out[i*4]   = (u8)(state[i] >> 24);
    out[i*4+1] = (u8)(state[i] >> 16);
    out[i*4+2] = (u8)(state[i] >>  8);
    out[i*4+3] = (u8)(state[i]);
  }
}

// ── Constant-time byte comparison (prevents timing attacks) ───────────────

static int ct_memcmp(const u8 *a, const u8 *b, size_t n) {
  volatile u8 diff = 0;
  for (size_t i = 0; i < n; i++) diff |= a[i] ^ b[i];
  return diff;
}

// ── JNI entry-point ───────────────────────────────────────────────────────

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_thunderdog_challegram_security_NativeSecurityBridge_nativeVerifyCert(
    JNIEnv *env, jobject /*thiz*/, jbyteArray certBytes)
{
  if (!certBytes) return JNI_FALSE;

  jsize   len  = env->GetArrayLength(certBytes);
  jbyte  *data = env->GetByteArrayElements(certBytes, nullptr);
  if (!data) return JNI_FALSE;

  u8 digest[32];
  sha256(reinterpret_cast<const u8 *>(data), (size_t)len, digest);
  env->ReleaseByteArrayElements(certBytes, data, JNI_ABORT);

  int eq = ct_memcmp(digest, EXPECTED_CERT_SHA256, 32);
  return (eq == 0) ? JNI_TRUE : JNI_FALSE;
}
