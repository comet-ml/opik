import React, { useEffect, useMemo, useState } from "react";
import api from "@/api/api";

// ---------------------------------------------------------------------------
// Binary helpers
// ---------------------------------------------------------------------------

function uuidFromBytes(bytes: Uint8Array): string {
  const h = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(
    16,
    20,
  )}-${h.slice(20)}`;
}

function uuidToBytes(uuid: string): Uint8Array {
  const hex = uuid.replace(/-/g, "");
  return Uint8Array.from({ length: 16 }, (_, i) =>
    parseInt(hex.slice(i * 2, i * 2 + 2), 16),
  );
}

// ---------------------------------------------------------------------------
// Fragment parsing
// ---------------------------------------------------------------------------

type RunnerType = "connect" | "endpoint";

interface PairingPayload {
  sessionId: string;
  activationKey: Uint8Array;
  projectId: string;
  runnerName: string;
  runnerType: RunnerType;
}

function parsePairingPayload(fragment: string): PairingPayload {
  const padded = fragment + "=".repeat((4 - (fragment.length % 4)) % 4);
  const raw = atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = Uint8Array.from(raw, (c) => c.charCodeAt(0));

  if (bytes.length < 65) throw new Error("bad link");

  const nameLen = bytes[64];
  if (bytes.length < 65 + nameLen) throw new Error("bad link");

  const typeOffset = 65 + nameLen;
  const runnerType: RunnerType =
    bytes.length > typeOffset && bytes[typeOffset] === 0x01
      ? "endpoint"
      : "connect";

  return {
    sessionId: uuidFromBytes(bytes.slice(0, 16)),
    activationKey: bytes.slice(16, 48),
    projectId: uuidFromBytes(bytes.slice(48, 64)),
    runnerName: new TextDecoder().decode(bytes.slice(65, 65 + nameLen)),
    runnerType,
  };
}

// ---------------------------------------------------------------------------
// Crypto (SubtleCrypto)
// ---------------------------------------------------------------------------

async function computeActivationHmac(
  activationKey: Uint8Array,
  sessionId: string,
  runnerName: string,
): Promise<string> {
  const sessionIdBytes = uuidToBytes(sessionId);
  const runnerNameHash = new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(runnerName)),
  );

  const message = new Uint8Array(48);
  message.set(sessionIdBytes, 0);
  message.set(runnerNameHash, 16);

  const key = await crypto.subtle.importKey(
    "raw",
    activationKey,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, message));
  return btoa(String.fromCharCode(...sig));
}

async function deriveBridgeKey(
  activationKey: Uint8Array,
  sessionId: string,
): Promise<Uint8Array> {
  const salt = uuidToBytes(sessionId);
  const info = new TextEncoder().encode("opik-bridge-v1");

  const prkKey = await crypto.subtle.importKey(
    "raw",
    salt,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const prk = new Uint8Array(
    await crypto.subtle.sign("HMAC", prkKey, activationKey),
  );

  const expandKey = await crypto.subtle.importKey(
    "raw",
    prk,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const expandMsg = new Uint8Array(info.length + 1);
  expandMsg.set(info, 0);
  expandMsg[info.length] = 0x01;
  return new Uint8Array(await crypto.subtle.sign("HMAC", expandKey, expandMsg));
}

// ---------------------------------------------------------------------------
// Activate + derive + store — runs once on mount
// ---------------------------------------------------------------------------

async function activate(payload: PairingPayload): Promise<void> {
  const workspace = new URLSearchParams(window.location.search).get(
    "workspace",
  );
  if (workspace) {
    api.defaults.headers.common["Comet-Workspace"] = workspace;
  }

  const hmac = await computeActivationHmac(
    payload.activationKey,
    payload.sessionId,
    payload.runnerName,
  );
  await api.post(`/v1/private/pairing/sessions/${payload.sessionId}/activate`, {
    runner_name: payload.runnerName,
    hmac,
  });

  // Only CONNECT runners use bridge keys for HMAC-signed file commands.
  // ENDPOINT runners don't need one — storing it would overwrite the
  // CONNECT runner's key and break bridge commands.
  if (payload.runnerType === "connect") {
    const bridgeKey = await deriveBridgeKey(
      payload.activationKey,
      payload.sessionId,
    );
    localStorage.setItem(
      `opik:bridgeKey:${payload.projectId}`,
      btoa(String.fromCharCode(...bridgeKey)),
    );
  }
}

// ---------------------------------------------------------------------------
// Page component
// ---------------------------------------------------------------------------

const PairingPage: React.FC = () => {
  const fragment = window.location.hash.slice(1);

  const [payload, parseError] = useMemo<
    [PairingPayload | null, string | null]
  >(() => {
    if (!fragment) return [null, "No pairing data in URL."];
    try {
      return [parsePairingPayload(fragment), null];
    } catch {
      return [null, "This pairing link is invalid."];
    }
  }, [fragment]);

  const [status, setStatus] = useState<"busy" | "done" | "error">(
    parseError ? "error" : "busy",
  );
  const [error, setError] = useState(parseError ?? "");

  // Auto-activate on mount
  useEffect(() => {
    if (!payload) return;
    if (!crypto?.subtle) {
      setStatus("error");
      setError("Pairing requires a secure connection (HTTPS).");
      return;
    }
    activate(payload)
      .then(() => setStatus("done"))
      .catch((err: unknown) => {
        setStatus("error");
        const s =
          err && typeof err === "object" && "response" in err
            ? (err as { response?: { status?: number } }).response?.status
            : undefined;
        if (s === 403)
          setError("This pairing link is invalid or has been tampered with.");
        else if (s === 404)
          setError("This pairing link has expired. Run the CLI command again.");
        else if (s === 409)
          setError("This runner is already connected. You can close this tab.");
        else setError("Could not reach Opik. Check your connection.");
      });
  }, [payload]);

  // Auto-close on success
  useEffect(() => {
    if (status !== "done") return;
    const t = setTimeout(() => window.close(), 1500);
    return () => clearTimeout(t);
  }, [status]);

  return (
    <p>
      {status === "done"
        ? "Connected ✔"
        : status === "error"
          ? error
          : "Connecting…"}
    </p>
  );
};

export default PairingPage;
