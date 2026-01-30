#!/usr/bin/env bun

/**
 * Cursor Hook Script for Agent Trace Logging
 * 
 * This script captures Cursor agent events and logs them to .agent-trace/traces.jsonl
 * in a format that can be processed by the Opik CLI to create traces with OpenAI-format messages.
 * 
 * Events captured:
 * - beforeSubmitPrompt: User input message
 * - afterAgentResponse: Agent's text response
 * - afterAgentThought: Agent's thinking/reasoning
 * - afterShellExecution: Shell command execution with output
 * - afterFileEdit: File modifications
 * - sessionStart/sessionEnd: Session lifecycle
 */

import {
  appendTrace,
  computeRangePositions,
  tryReadFile,
  type FileEdit,
} from "./trace-store";

interface HookInput {
  hook_event_name: string;
  // Common fields
  model?: string;
  transcript_path?: string | null;
  conversation_id?: string;
  generation_id?: string;
  session_id?: string;
  cursor_version?: string;
  workspace_roots?: string[];
  user_email?: string | null;
  
  // beforeSubmitPrompt
  prompt?: string;
  attachments?: Array<{ type: string; filePath: string }>;
  
  // afterAgentResponse / afterAgentThought
  text?: string;
  duration_ms?: number;
  
  // afterShellExecution
  command?: string;
  output?: string;
  duration?: number;
  cwd?: string;
  
  // afterFileEdit
  file_path?: string;
  edits?: FileEdit[];
  
  // preToolUse / postToolUse
  tool_name?: string;
  tool_input?: { file_path?: string; new_string?: string; old_string?: string; command?: string; working_directory?: string };
  tool_use_id?: string;
  tool_output?: string;
  agent_message?: string;  // Assistant text written BEFORE the tool call
  
  // sessionStart/sessionEnd
  is_background_agent?: boolean;
  composer_mode?: string;
  reason?: string;
  final_status?: string;
  error_message?: string;
  
  // Legacy/other fields
  source?: string;
}

interface TraceRecord {
  version: string;
  id: string;
  timestamp: string;
  event: string;
  conversation_id?: string;
  generation_id?: string;
  model?: string;
  data: Record<string, unknown>;
}

function generateId(): string {
  return crypto.randomUUID();
}

function createRecord(event: string, input: HookInput, data: Record<string, unknown>): TraceRecord {
  return {
    version: "1.0",
    id: generateId(),
    timestamp: new Date().toISOString(),
    event,
    conversation_id: input.conversation_id,
    generation_id: input.generation_id,
    model: input.model,
    data,
  };
}

const handlers: Record<string, (input: HookInput) => void> = {
  /**
   * Captures user input before it's sent to the agent.
   * This becomes the "user" role message in OpenAI format.
   */
  beforeSubmitPrompt: (input) => {
    const record = createRecord("user_message", input, {
      content: input.prompt,
      attachments: input.attachments,
    });
    appendTrace(record);
  },

  /**
   * preToolUse hook - fires before a tool is executed.
   * Note: Cursor does NOT provide agent_message in this hook (as of v2.4.23),
   * so we can't capture the assistant's text before tool calls.
   */
  preToolUse: (_input) => {
    // No useful data available - agent_message is not provided by Cursor
  },

  /**
   * Captures the agent's final text response.
   * This becomes the "assistant" role message in OpenAI format.
   */
  afterAgentResponse: (input) => {
    const record = createRecord("assistant_message", input, {
      content: input.text,
    });
    appendTrace(record);
  },

  /**
   * Captures the agent's thinking/reasoning.
   * Currently disabled - thoughts are excluded from logging.
   */
  afterAgentThought: (_input) => {
    // Disabled: thoughts are excluded from logging
  },

  /**
   * Captures shell command execution.
   * This becomes a "tool" role message with tool_call_id.
   */
  afterShellExecution: (input) => {
    const record = createRecord("tool_execution", input, {
      tool_type: "shell",
      command: input.command,
      output: input.output,
      duration_ms: input.duration,
      cwd: input.cwd,
    });
    appendTrace(record);
  },

  /**
   * Captures file edits.
   * This becomes a "tool" role message with tool_call_id.
   */
  afterFileEdit: (input) => {
    const rangePositions = computeRangePositions(input.edits ?? [], tryReadFile(input.file_path!));
    const record = createRecord("tool_execution", input, {
      tool_type: "file_edit",
      file_path: input.file_path,
      edits: input.edits,
      line_ranges: rangePositions,
    });
    appendTrace(record);
  },

  /**
   * Captures Tab file edits (inline completions).
   */
  afterTabFileEdit: (input) => {
    const rangePositions = computeRangePositions(input.edits ?? []);
    const record = createRecord("tab_file_edit", input, {
      tool_type: "tab_edit",
      file_path: input.file_path,
      edits: input.edits,
      line_ranges: rangePositions,
    });
    appendTrace(record);
  },

  /**
   * Captures session start.
   */
  sessionStart: (input) => {
    const record = createRecord("session_start", input, {
      session_id: input.session_id,
      is_background_agent: input.is_background_agent,
      composer_mode: input.composer_mode,
    });
    appendTrace(record);
  },

  /**
   * Captures session end.
   */
  sessionEnd: (input) => {
    const record = createRecord("session_end", input, {
      session_id: input.session_id,
      reason: input.reason,
      duration_ms: input.duration_ms,
      final_status: input.final_status,
      error_message: input.error_message,
    });
    appendTrace(record);
  },

  /**
   * Captures the agent loop completion.
   */
  stop: (input) => {
    const record = createRecord("agent_stop", input, {
      status: (input as any).status,
      loop_count: (input as any).loop_count,
    });
    appendTrace(record);
  },

  // Legacy handlers for backward compatibility
  SessionStart: (input) => handlers.sessionStart(input),
  SessionEnd: (input) => handlers.sessionEnd(input),
  
  PostToolUse: (input) => {
    const toolName = input.tool_name ?? "";
    const isFileEdit = toolName === "Write" || toolName === "Edit";
    const isBash = toolName === "Bash";

    if (!isFileEdit && !isBash) return;

    const record = createRecord("tool_execution", input, {
      tool_type: isBash ? "shell" : "file_edit",
      tool_name: toolName,
      tool_use_id: input.tool_use_id,
      file_path: isFileEdit ? input.tool_input?.file_path : undefined,
      command: isBash ? input.tool_input?.command : undefined,
      edits: isFileEdit ? [{
        old_string: input.tool_input?.old_string ?? "",
        new_string: input.tool_input?.new_string ?? "",
      }] : undefined,
    });
    appendTrace(record);
  },
};

async function main() {
  const chunks: Buffer[] = [];
  for await (const chunk of Bun.stdin.stream()) {
    chunks.push(Buffer.from(chunk));
  }

  const json = Buffer.concat(chunks).toString("utf-8").trim();
  if (!json) process.exit(0);

  try {
    const input = JSON.parse(json) as HookInput;
    const handler = handlers[input.hook_event_name];
    if (handler) {
      handler(input);
    } else {
      // Log unknown events for debugging
      console.error(`Unknown hook event: ${input.hook_event_name}`);
    }
  } catch (e) {
    console.error("Hook error:", e);
    process.exit(1);
  }
}

main();
