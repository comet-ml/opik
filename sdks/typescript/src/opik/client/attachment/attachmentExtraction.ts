import { detectMimeType, fileExtensionForMimeType } from "./mimeTypes";

/**
 * Client-side base64 attachment extraction (parity with the Python SDK, OPIK-7335 family).
 *
 * Large inline base64 blobs (e.g. images) in a span/trace's `input`/`output`/`metadata`
 * bloat the payload and can trip the per-object size cap. This walks those fields, replaces
 * each recognized blob with a compact `[<context>-attachment-...-sdk.<ext>]` placeholder,
 * and returns the decoded bytes so the caller can upload them as real attachments. Running
 * this BEFORE the size measurement is what lets images bypass the cap.
 *
 * Pure and non-mutating: returns the same reference when nothing was extracted.
 */

const FIELDS = ["input", "output", "metadata"] as const;
type Field = (typeof FIELDS)[number];

type AttachmentSource = {
  input?: unknown;
  output?: unknown;
  metadata?: unknown;
};

export interface ExtractedAttachment {
  data: Buffer;
  fileName: string;
  mimeType: string;
}

const createAttachmentFileName = (
  context: string,
  mimeType: string,
): string => {
  const random = Math.floor(Math.random() * 99999999) + 1;
  const extension = fileExtensionForMimeType(mimeType);
  return `${context}-attachment-${random}-${Date.now()}-sdk.${extension}`;
};

// Buffer.from(str, "base64") never throws (invalid chars are ignored), so no try/catch:
// an all-invalid / empty input just decodes to zero bytes, which we treat as "not a blob".
const decodeBase64 = (base64: string): Buffer | null => {
  const decoded = Buffer.from(base64, "base64");
  return decoded.length > 0 ? decoded : null;
};

// Base64 runs are found with a manual character scan rather than a regex: V8's regex engine
// can backtrack quadratically (and matchAll can overflow the stack) on multi-MB strings that
// repeatedly almost-match the base64 alphabet, so a linear scan is the only thing that scales.
const isBase64Char = (code: number): boolean =>
  (code >= 65 && code <= 90) || // A-Z
  (code >= 97 && code <= 122) || // a-z
  (code >= 48 && code <= 57) || // 0-9
  code === 43 || // +
  code === 47; // /
const EQUALS = 61; // '='

// An optional `data:<mime>;base64,` prefix immediately before a run is detected with a tiny
// regex on a short look-back slice (safe — bounded length) so the whole URI is replaced.
const DATA_URI_PREFIX_RE = /data:[^,]*;base64,$/;
const PREFIX_LOOKBACK = 128;

const extractFromString = (
  value: string,
  context: Field,
  attachments: ExtractedAttachment[],
  minChars: number,
): string => {
  // A blob can't be a match if the whole string is shorter than the minimum (parity with
  // the Python SDK), and skipping short strings up front avoids scanning ordinary text.
  if (value.length < minChars) {
    return value;
  }
  const parts: string[] = [];
  let lastEnd = 0;
  let changed = false;
  const n = value.length;
  let i = 0;

  while (i < n) {
    if (!isBase64Char(value.charCodeAt(i))) {
      i++;
      continue;
    }
    // Consume a maximal run of base64 chars, then up to two '=' padding chars.
    let end = i;
    while (end < n && isBase64Char(value.charCodeAt(end))) {
      end++;
    }
    let pad = 0;
    while (end < n && value.charCodeAt(end) === EQUALS && pad < 2) {
      end++;
      pad++;
    }

    const runLength = end - i;
    if (runLength >= minChars) {
      const decoded = decodeBase64(value.slice(i, end));
      const mimeType = decoded ? detectMimeType(decoded) : null;
      // Leave unrecognized blobs (plain base64, unknown types) inline, like the Python SDK.
      if (decoded && mimeType) {
        // Absorb a preceding `data:<mime>;base64,` prefix so the whole URI is replaced.
        let replaceStart = i;
        const lookback = value.slice(Math.max(0, i - PREFIX_LOOKBACK), i);
        const prefixMatch = lookback.match(DATA_URI_PREFIX_RE);
        if (prefixMatch) {
          replaceStart = i - prefixMatch[0].length;
        }
        const fileName = createAttachmentFileName(context, mimeType);
        parts.push(value.slice(lastEnd, replaceStart));
        parts.push(`[${fileName}]`);
        lastEnd = end;
        attachments.push({ data: decoded, fileName, mimeType });
        changed = true;
      }
    }
    i = end;
  }

  if (!changed) {
    return value;
  }
  parts.push(value.slice(lastEnd));
  return parts.join("");
};

// `seen` guards against circular references: a payload with a cycle would otherwise recurse
// forever. An already-visited object/array is returned unchanged (nothing to extract there).
const walk = (
  value: unknown,
  context: Field,
  attachments: ExtractedAttachment[],
  minChars: number,
  seen: WeakSet<object>,
): unknown => {
  if (typeof value === "string") {
    return extractFromString(value, context, attachments, minChars);
  }
  if (Array.isArray(value)) {
    if (seen.has(value)) return value;
    seen.add(value);
    let changed = false;
    const out = value.map((element) => {
      const walked = walk(element, context, attachments, minChars, seen);
      if (walked !== element) changed = true;
      return walked;
    });
    return changed ? out : value;
  }
  if (value !== null && typeof value === "object") {
    if (seen.has(value)) return value;
    seen.add(value);
    let changed = false;
    const out: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) {
      const walked = walk(item, context, attachments, minChars, seen);
      if (walked !== item) changed = true;
      out[key] = walked;
    }
    return changed ? out : value;
  }
  return value;
};

/**
 * Walk `input`/`output`/`metadata`, replacing base64 blobs at least `minSizeBytes` long
 * with placeholders. Returns a shallow copy with the replacements applied (or the original
 * reference if nothing was extracted) plus the list of decoded attachments.
 */
export const extractInlineAttachments = <T extends AttachmentSource>(
  payload: T,
  minSizeBytes: number,
): { result: T; attachments: ExtractedAttachment[] } => {
  // Threshold on base64 character length: 4 base64 chars encode 3 bytes, and the Python SDK
  // gates on `floor(minSizeBytes / 4)` groups of 4 — mirror that exactly for parity.
  const minChars = Math.max(4, Math.floor(minSizeBytes / 4) * 4);

  const attachments: ExtractedAttachment[] = [];
  const overrides: Partial<Record<Field, unknown>> = {};
  for (const field of FIELDS) {
    const original = payload[field];
    if (original == null) {
      continue;
    }
    // A fresh `seen` per field: the guard is only for cycles within one field's own graph.
    const walked = walk(original, field, attachments, minChars, new WeakSet());
    if (walked !== original) {
      overrides[field] = walked;
    }
  }

  if (attachments.length === 0) {
    return { result: payload, attachments: [] };
  }
  return { result: { ...payload, ...overrides } as T, attachments };
};
