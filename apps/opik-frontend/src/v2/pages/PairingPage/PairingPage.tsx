import React, { useEffect, useMemo } from "react";
import { useMutation } from "@tanstack/react-query";
import api from "@/api/api";
import PairingStatusScreen, {
  PairingErrorKind,
  PairingStatusScreenProps,
  RunnerVariant,
} from "@/shared/PairingStatusScreen/PairingStatusScreen";

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

interface PairingPayload {
  sessionId: string;
  activationKey: Uint8Array;
  projectId: string;
  runnerName: string;
  runnerType: RunnerVariant;
}

function parsePairingPayload(fragment: string): PairingPayload {
  const padded = fragment + "=".repeat((4 - (fragment.length % 4)) % 4);
  const raw = atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = Uint8Array.from(raw, (c) => c.charCodeAt(0));

  if (bytes.length < 65) throw new Error("bad link");

  const nameLen = bytes[64];
  if (bytes.length < 65 + nameLen) throw new Error("bad link");

  const typeOffset = 65 + nameLen;
  const runnerType: RunnerVariant =
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

async function activate(
  payload: PairingPayload,
  workspace: string | null,
): Promise<void> {
  const hmac = await computeActivationHmac(
    payload.activationKey,
    payload.sessionId,
    payload.runnerName,
  );
  await api.post(
    `/v1/private/pairing/sessions/${payload.sessionId}/activate`,
    { runner_name: payload.runnerName, hmac },
    workspace ? { headers: { "Comet-Workspace": workspace } } : undefined,
  );

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

function httpStatus(err: unknown): number | undefined {
  if (err && typeof err === "object" && "response" in err) {
    return (err as { response?: { status?: number } }).response?.status;
  }
  return undefined;
}

function mapActivationError(err: unknown): PairingErrorKind {
  if (err instanceof Error && err.message === "SECURE_CONTEXT_REQUIRED") {
    return "insecure_context";
  }
  const status = httpStatus(err);
  if (status === 403) return "tampered_link";
  if (status === 404) return "expired_link";
  return "unreachable";
}

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

  const workspaceName = new URLSearchParams(window.location.search).get(
    "workspace",
  );

  const {
    mutate: runActivation,
    isIdle: activationIdle,
    isError: activationIsError,
    isSuccess: activationIsSuccess,
    error: activationError,
  } = useMutation({
    mutationFn: async (p: PairingPayload) => {
      if (!crypto?.subtle) throw new Error("SECURE_CONTEXT_REQUIRED");
      try {
        await activate(p, workspaceName);
      } catch (err) {
        // 409 = session already activated; treat as success so repeat opens
        // still land on the success screen rather than an error.
        if (httpStatus(err) !== 409) throw err;
      }
    },
  });

  useEffect(() => {
    if (!payload || !workspaceName || !activationIdle) return;
    runActivation(payload);
  }, [payload, workspaceName, activationIdle, runActivation]);

  let screenProps: PairingStatusScreenProps;
  if (parseError || !workspaceName) {
    screenProps = { status: "error", errorKind: "invalid_link" };
  } else if (activationIsError) {
    screenProps = {
      status: "error",
      errorKind: mapActivationError(activationError),
    };
  } else if (activationIsSuccess) {
    screenProps = { status: "success", runnerVariant: payload?.runnerType };
  } else {
    screenProps = { status: "loading", runnerVariant: payload?.runnerType };
  }

  return <PairingStatusScreen {...screenProps} />;
};

export default PairingPage;
