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

interface PairingPayload {
  sessionId: string;
  activationKey: Uint8Array;
  projectId: string;
  runnerName: string;
}

function parsePairingPayload(fragment: string): PairingPayload {
  const padded = fragment + "=".repeat((4 - (fragment.length % 4)) % 4);
  const raw = atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = Uint8Array.from(raw, (c) => c.charCodeAt(0));

  if (bytes.length < 65) throw new Error("bad link");

  const nameLen = bytes[64];
  if (bytes.length < 65 + nameLen) throw new Error("bad link");

  return {
    sessionId: uuidFromBytes(bytes.slice(0, 16)),
    activationKey: bytes.slice(16, 48),
    projectId: uuidFromBytes(bytes.slice(48, 64)),
    runnerName: new TextDecoder().decode(bytes.slice(65, 65 + nameLen)),
  };
}

// ---------------------------------------------------------------------------
// HMAC computation (SubtleCrypto)
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

  // Standard base64 WITH padding — backend uses Java's Base64.getDecoder()
  return btoa(String.fromCharCode(...sig));
}

// ---------------------------------------------------------------------------
// HKDF-SHA256 bridge key derivation (same as CLI)
// ---------------------------------------------------------------------------

async function deriveBridgeKey(
  activationKey: Uint8Array,
  sessionId: string,
): Promise<Uint8Array> {
  const salt = uuidToBytes(sessionId);
  const info = new TextEncoder().encode("opik-bridge-v1");

  // Extract: PRK = HMAC-SHA256(salt, ikm)
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

  // Expand: OKM = HMAC-SHA256(PRK, info || 0x01)
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
// Page component
// ---------------------------------------------------------------------------

type Status = "confirm" | "busy" | "done" | "error";

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

  const [status, setStatus] = useState<Status>(
    parseError ? "error" : "confirm",
  );
  const [error, setError] = useState(parseError ?? "");

  // Check for SubtleCrypto on mount
  useEffect(() => {
    if (!crypto?.subtle) {
      setStatus("error");
      setError("Pairing requires a secure connection (HTTPS).");
    }
  }, []);

  // Auto-close on success
  useEffect(() => {
    if (status !== "done") return;
    const t = setTimeout(() => window.close(), 1500);
    return () => clearTimeout(t);
  }, [status]);

  async function handleConnect() {
    if (!payload) return;
    setStatus("busy");
    try {
      const hmac = await computeActivationHmac(
        payload.activationKey,
        payload.sessionId,
        payload.runnerName,
      );
      await api.post(
        `/v1/private/opik-connect/sessions/${payload.sessionId}/activate`,
        { runner_name: payload.runnerName, hmac },
      );
      const bridgeKey = await deriveBridgeKey(
        payload.activationKey,
        payload.sessionId,
      );
      localStorage.setItem(
        `opik:bridgeKey:${payload.projectId}`,
        btoa(String.fromCharCode(...bridgeKey)),
      );
      setStatus("done");
    } catch (err: unknown) {
      setStatus("error");
      const status =
        err && typeof err === "object" && "response" in err
          ? (err as { response?: { status?: number } }).response?.status
          : undefined;
      if (status === 403)
        setError("This pairing link is invalid or has been tampered with.");
      else if (status === 404)
        setError("This pairing link has expired. Run the CLI command again.");
      else if (status === 409)
        setError("This runner is already connected. You can close this tab.");
      else setError("Could not reach Opik. Check your connection.");
    }
  }

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontFamily: "system-ui, sans-serif",
      }}
    >
      {status === "done" ? (
        <div style={{ textAlign: "center" }}>
          <p style={{ fontSize: 20 }}>Connected ✔</p>
          <p style={{ color: "#888", marginTop: 8 }}>Closing tab…</p>
        </div>
      ) : status === "error" ? (
        <div style={{ textAlign: "center", maxWidth: 400 }}>
          <p style={{ color: "#d33", marginBottom: 16 }}>{error}</p>
          <button onClick={() => window.close()}>Close</button>
        </div>
      ) : (
        <div
          style={{
            border: "1px solid #ddd",
            borderRadius: 8,
            padding: 32,
            minWidth: 320,
          }}
        >
          <h2 style={{ margin: "0 0 16px", fontSize: 18 }}>Connect runner?</h2>
          <table style={{ marginBottom: 24 }}>
            <tbody>
              <tr>
                <td style={{ color: "#888", paddingRight: 16 }}>Runner</td>
                <td>
                  <strong>{payload?.runnerName}</strong>
                </td>
              </tr>
            </tbody>
          </table>
          <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
            <button onClick={() => window.close()}>Cancel</button>
            <button onClick={handleConnect} disabled={status === "busy"}>
              {status === "busy" ? "Connecting…" : "Connect"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default PairingPage;
