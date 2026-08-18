import { randomBytes } from 'node:crypto';

/**
 * Build a UUIDv7 whose embedded timestamp is `moment` (default: now).
 *
 * Opik ids are version 7 and the backend reads the creation instant back out of
 * them — time-windowed reads window on the id's timestamp, not on `start_time`.
 * So a test that needs a trace to sit provably inside or outside a rolling
 * window has to control the id, and a test that needs to assert on exact ids
 * has to mint them up front (the REST writes answer 204 with no body).
 *
 * Bit layout, per RFC 9562: 48-bit big-endian millisecond timestamp, 4-bit
 * version 7, 12 random bits, 2-bit variant, 62 random bits. This mirrors
 * `_uuid7_at` in the SDK driver, which exists for the same reason on the Python
 * side.
 */
export function uuid7(moment: Date = new Date()): string {
  const bytes = randomBytes(16);
  const millis = moment.getTime();

  // 48-bit timestamp, most significant byte first.
  bytes[0] = (millis / 2 ** 40) & 0xff;
  bytes[1] = (millis / 2 ** 32) & 0xff;
  bytes[2] = (millis / 2 ** 24) & 0xff;
  bytes[3] = (millis / 2 ** 16) & 0xff;
  bytes[4] = (millis / 2 ** 8) & 0xff;
  bytes[5] = millis & 0xff;

  // Version 7 in the high nibble of byte 6; RFC 4122 variant in byte 8.
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = bytes.toString('hex');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join('-');
}
