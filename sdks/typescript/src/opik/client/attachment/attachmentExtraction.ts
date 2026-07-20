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
 * Blobs are found with a regex, matching the Python SDK. `pattern.exec` in a loop (not
 * `String.matchAll`, which builds every match up front and can overflow V8's stack on
 * multi-MB inputs). The pattern is a single character class with a fixed+unbounded
 * quantifier, so it matches in linear time without catastrophic backtracking.
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

// The matched base64 alphabet is valid by construction, so Buffer.from is safe and never
// throws (invalid chars would just be ignored); an empty decode means "not a real blob".
const decodeBase64 = (base64: string): Buffer | null => {
  const decoded = Buffer.from(base64, "base64");
  return decoded.length > 0 ? decoded : null;
};

// The regex depends only on minGroups (fixed per client), so compile it once and reuse.
// lastIndex is reset before each use in extractFromString, so a shared instance is safe.
const patternCache = new Map<number, RegExp>();
const patternForGroups = (minGroups: number): RegExp => {
  let pattern = patternCache.get(minGroups);
  if (!pattern) {
    pattern = new RegExp(
      `(?<prefix>data:[^,]*;base64,)?(?<base64>(?:[A-Za-z0-9+/]{4}){${minGroups},}(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?)`,
      "g",
    );
    patternCache.set(minGroups, pattern);
  }
  return pattern;
};

const extractFromString = (
  value: string,
  context: Field,
  attachments: ExtractedAttachment[],
  pattern: RegExp,
  minChars: number,
): string => {
  // A blob can't be a match if the whole string is shorter than the minimum (parity with
  // the Python SDK), and skipping short strings up front avoids scanning ordinary text.
  if (value.length < minChars) {
    return value;
  }
  pattern.lastIndex = 0;
  const parts: string[] = [];
  let lastEnd = 0;
  let changed = false;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(value)) !== null) {
    const base64 = match.groups?.base64 ?? "";
    const decoded = decodeBase64(base64);
    const mimeType = decoded ? detectMimeType(decoded) : null;
    // Leave unrecognized blobs (plain base64, unknown types) inline, like the Python SDK.
    if (!decoded || !mimeType) {
      continue;
    }
    const fileName = createAttachmentFileName(context, mimeType);
    // match[0] includes the optional `data:<mime>;base64,` prefix, so the whole URI is replaced.
    parts.push(value.slice(lastEnd, match.index));
    parts.push(`[${fileName}]`);
    lastEnd = match.index + match[0].length;
    attachments.push({ data: decoded, fileName, mimeType });
    changed = true;
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
  pattern: RegExp,
  minChars: number,
  seen: WeakSet<object>,
): unknown => {
  if (typeof value === "string") {
    return extractFromString(value, context, attachments, pattern, minChars);
  }
  if (Array.isArray(value)) {
    if (seen.has(value)) return value;
    seen.add(value);
    let changed = false;
    const out = value.map((element) => {
      const walked = walk(element, context, attachments, pattern, minChars, seen);
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
      const walked = walk(item, context, attachments, pattern, minChars, seen);
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
  const minGroups = Math.max(1, Math.floor(minSizeBytes / 4));
  const minChars = minGroups * 4;
  const pattern = patternForGroups(minGroups);

  const attachments: ExtractedAttachment[] = [];
  const overrides: Partial<Record<Field, unknown>> = {};
  for (const field of FIELDS) {
    const original = payload[field];
    if (original == null) {
      continue;
    }
    // A fresh `seen` per field: the guard is only for cycles within one field's own graph.
    const walked = walk(
      original,
      field,
      attachments,
      pattern,
      minChars,
      new WeakSet(),
    );
    if (walked !== original) {
      overrides[field] = walked;
    }
  }

  if (attachments.length === 0) {
    return { result: payload, attachments: [] };
  }
  return { result: { ...payload, ...overrides } as T, attachments };
};
