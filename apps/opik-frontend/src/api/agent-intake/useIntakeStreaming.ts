import { useCallback } from "react";
import {
  INTAKE_EVENT_TYPE,
  IntakeStreamEvent,
  IntakeStartRequest,
  INPUT_HINT,
  IntakeConfig,
} from "@/types/agent-intake";

const INTAKE_BASE_URL = "http://localhost:5008";

type StreamCallbacks = {
  onTextDelta: (content: string) => void;
  onTextDone: () => void;
  onInputHint: (hint: INPUT_HINT) => void;
  onConfigUpdated: (config: IntakeConfig | undefined, isReady: boolean) => void;
  onTurnComplete: (isReady: boolean) => void;
  onComplete: (config: IntakeConfig | undefined) => void;
};

async function parseSSEStream(
  response: Response,
  callbacks: StreamCallbacks,
  signal: AbortSignal,
): Promise<{ error: string | null }> {
  if (!response.ok || !response.body) {
    return { error: `HTTP error: ${response.status}` };
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split(/\r?\n\r?\n/);
      buffer = events.pop() || "";

      for (const eventStr of events) {
        if (!eventStr.trim()) continue;

        const lines = eventStr.split(/\r?\n/);
        let eventType = "message";
        let data: IntakeStreamEvent | null = null;

        for (const line of lines) {
          if (line.startsWith("event:")) {
            eventType = line.slice(6).trim();
          } else if (line.startsWith("data:")) {
            try {
              data = JSON.parse(line.slice(5).trim()) as IntakeStreamEvent;
            } catch {
              // ignore parse errors
            }
          }
        }

        if (!data) continue;

        switch (data.type) {
          case INTAKE_EVENT_TYPE.text_delta:
            if (data.content) {
              callbacks.onTextDelta(data.content);
            }
            break;
          case INTAKE_EVENT_TYPE.text_done:
            callbacks.onTextDone();
            break;
          case INTAKE_EVENT_TYPE.input_hint:
            if (data.hint) {
              callbacks.onInputHint(data.hint);
            }
            break;
          case INTAKE_EVENT_TYPE.config_updated:
            callbacks.onConfigUpdated(data.config, data.is_ready ?? false);
            break;
          case INTAKE_EVENT_TYPE.turn_complete:
            callbacks.onTurnComplete(data.is_ready ?? false);
            break;
          case INTAKE_EVENT_TYPE.intake_complete:
            callbacks.onComplete(data.config);
            break;
        }

        if (eventType === "complete" && data.config) {
          callbacks.onComplete(data.config);
        }
      }
    }
  } catch (error) {
    if (signal.aborted) {
      return { error: null };
    }
    return { error: (error as Error).message };
  }

  return { error: null };
}

export function useIntakeStart(traceId: string) {
  return useCallback(
    async (
      request: IntakeStartRequest,
      callbacks: StreamCallbacks,
      signal: AbortSignal,
    ) => {
      const response = await fetch(
        `${INTAKE_BASE_URL}/intake/${traceId}/start`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(request),
          signal,
        },
      );

      return parseSSEStream(response, callbacks, signal);
    },
    [traceId],
  );
}

export function useIntakeRespond(traceId: string) {
  return useCallback(
    async (
      message: string,
      callbacks: StreamCallbacks,
      signal: AbortSignal,
    ) => {
      const response = await fetch(
        `${INTAKE_BASE_URL}/intake/${traceId}/respond`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ message }),
          signal,
        },
      );

      return parseSSEStream(response, callbacks, signal);
    },
    [traceId],
  );
}

export function useIntakeDelete(traceId: string) {
  return useCallback(async () => {
    await fetch(`${INTAKE_BASE_URL}/intake/${traceId}`, {
      method: "DELETE",
    });
  }, [traceId]);
}
