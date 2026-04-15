import React, { useEffect, useMemo } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { CheckCircle2, CircleAlert } from "lucide-react";
import api from "@/api/api";
import { fetchWorkspaceVersion } from "@/api/workspaces/useWorkspaceVersion";
import { Spinner } from "@/ui/spinner";

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

// ---------------------------------------------------------------------------
// Page component
// ---------------------------------------------------------------------------

type WorkspacePhase = "missing" | "checking" | "ok" | "v1";
type Status = "loading" | "success" | "error";

const PairingPage: React.FC = () => {
  const navigate = useNavigate();
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

  const versionQuery = useQuery({
    queryKey: ["pairing-workspace-version", workspaceName],
    queryFn: ({ signal }) =>
      fetchWorkspaceVersion({ workspaceName: workspaceName!, signal }),
    enabled: !!workspaceName,
    staleTime: 5 * 60 * 1000,
  });

  const workspacePhase: WorkspacePhase = useMemo(() => {
    if (!workspaceName) return "missing";
    if (versionQuery.isPending) return "checking";
    if (versionQuery.data === "v2") return "ok";
    return "v1";
  }, [workspaceName, versionQuery.isPending, versionQuery.data]);

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
        // 409 = runner already paired; treat as success so the user still
        // gets redirected to their agent instead of seeing an error screen.
        const status =
          err && typeof err === "object" && "response" in err
            ? (err as { response?: { status?: number } }).response?.status
            : undefined;
        if (status !== 409) throw err;
      }
    },
    onSuccess: (_data, variables) => {
      if (!workspaceName) return;
      navigate({
        to: "/$workspaceName/projects/$projectId/agent-configuration",
        params: { workspaceName, projectId: variables.projectId },
      });
    },
  });

  useEffect(() => {
    if (workspacePhase !== "ok" || !payload || !activationIdle) return;
    runActivation(payload);
  }, [workspacePhase, payload, activationIdle, runActivation]);

  function getActivationErrorMessage(err: unknown): string {
    if (err instanceof Error && err.message === "SECURE_CONTEXT_REQUIRED") {
      return "Pairing requires a secure connection (HTTPS).";
    }
    const s =
      err && typeof err === "object" && "response" in err
        ? (err as { response?: { status?: number } }).response?.status
        : undefined;
    if (s === 403)
      return "This pairing link is invalid or has been tampered with.";
    if (s === 404)
      return "This pairing link has expired. Run the CLI command again.";
    return "Could not reach Opik. Check your connection.";
  }

  function getDisplay(): { status: Status; message: string } {
    // Fragment-level errors surface first: a bad link is invalid regardless
    // of workspace state, and we shouldn't block on the version query to
    // tell the user.
    if (parseError) return { status: "error", message: parseError };
    if (workspacePhase === "missing") {
      return { status: "error", message: "This pairing link is invalid." };
    }
    if (workspacePhase === "v1") {
      return {
        status: "error",
        message:
          "Opik Connect requires Opik 2.0. Please upgrade your workspace to continue.",
      };
    }
    if (workspacePhase === "checking") {
      return { status: "loading", message: "Connecting…" };
    }
    if (activationIsError) {
      return {
        status: "error",
        message: getActivationErrorMessage(activationError),
      };
    }
    if (activationIsSuccess) return { status: "success", message: "Connected" };
    return { status: "loading", message: "Connecting…" };
  }

  const { status, message } = getDisplay();

  const icon =
    status === "loading" ? (
      <Spinner size="medium" />
    ) : status === "success" ? (
      <CheckCircle2 className="size-8 shrink-0 text-green-600" />
    ) : (
      <CircleAlert className="size-8 shrink-0 text-destructive" />
    );

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-3 p-6">
      {icon}
      <p className="comet-body text-center text-muted-slate">{message}</p>
    </div>
  );
};

export default PairingPage;
