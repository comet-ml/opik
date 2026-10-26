import {
  ArtifactProposal,
  ArtifactRejection,
  ArtifactResult,
} from "@/types/assistant-sidebar";

export type ArtifactHandler = (
  proposal: ArtifactProposal,
) => ArtifactRejection | null;

const handlers = new Map<string, ArtifactHandler>();

export const registerArtifactHandler = (
  kind: string,
  handler: ArtifactHandler,
) => {
  handlers.set(kind, handler);
  return () => {
    if (handlers.get(kind) === handler) handlers.delete(kind);
  };
};

export const applyArtifact = (proposal: ArtifactProposal): ArtifactResult => {
  const handler = handlers.get(proposal.kind);
  if (!handler) {
    return {
      proposalId: proposal.proposalId,
      applied: false,
      reason: "no_handler",
    };
  }
  let reason: ArtifactRejection | null;
  try {
    reason = handler(proposal);
  } catch {
    reason = "invalid";
  }
  return reason
    ? { proposalId: proposal.proposalId, applied: false, reason }
    : { proposalId: proposal.proposalId, applied: true };
};
