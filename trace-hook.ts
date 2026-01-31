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
  user_email?: string | null;
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
    user_email: input.user_email,
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
   * Captures file edits from Agent.
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
   * Captures the agent loop completion.
   */
  stop: (input) => {
    const record = createRecord("agent_stop", input, {
      status: (input as any).status,
      loop_count: (input as any).loop_count,
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
    }
    // Silently ignore unknown events
  } catch (e) {
    console.error("Hook error:", e);
    process.exit(1);
  }
}

main();
