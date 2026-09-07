export type BubbleKind = 'user' | 'tool' | 'error' | 'thinking' | 'message' | 'skip';

/**
 * Cursor renamed most tools between versions (read_file -> read_file_v2, grep ->
 * ripgrep_raw_search, search_replace -> edit_file_v2) but never changed the
 * numeric id, so the id is the only stable label.
 */
export const TOOL_ID_NAMES: Record<number, string> = {
    8: 'file_search',
    9: 'codebase_search',
    11: 'delete_file',
    15: 'run_terminal_cmd',
    18: 'web_search',
    19: 'mcp_tool',
    30: 'read_lints',
    35: 'todo_write',
    38: 'edit_file',
    39: 'list_dir',
    40: 'read_file',
    41: 'grep',
    42: 'glob_file_search',
    43: 'create_plan',
    48: 'task',
    51: 'ask_question',
    57: 'web_fetch',
    90: 'acp_tool',
};

function toolId(toolFormerData: any): number | undefined {
    const raw = toolFormerData?.tool;
    const parsed = typeof raw === 'string' ? Number(raw) : raw;
    return typeof parsed === 'number' && Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * Ordinary user and text bubbles carry a placeholder
 * `toolFormerData: {"additionalData": {"status": "error"}}` with no tool and no
 * name. 1237 of the 19691 bubbles in a real database look like that, so the
 * presence of the object alone does not mean the bubble is a tool call.
 */
export function isToolCall(bubble: any): boolean {
    const data = bubble?.toolFormerData;
    if (!data) {
        return false;
    }
    return toolId(data) !== undefined || typeof data.name === 'string' && data.name.length > 0;
}

export function toolName(toolFormerData: any): string {
    const name = toolFormerData?.name;
    if (typeof name === 'string' && name.trim()) {
        return name.trim();
    }
    const id = toolId(toolFormerData);
    return (id !== undefined && TOOL_ID_NAMES[id]) || 'unknown_tool';
}

export function classifyBubble(bubble: any): BubbleKind {
    if (!bubble) {
        return 'skip';
    }
    // Raw bubbles from SQLite use the numeric type. readCursorChatDataAsync
    // rewrites it to 'user' / 'ai' before the traces are built, so both forms
    // reach this function.
    if (bubble.type === 1 || bubble.type === 'user') {
        return 'user';
    }
    // Checked before thinking: a bubble can carry both, and the tool call is
    // the part that has inputs and outputs worth a span.
    if (isToolCall(bubble)) {
        return 'tool';
    }
    if (bubble.errorDetails?.message) {
        return 'error';
    }
    if (bubble.thinking?.text) {
        return 'thinking';
    }
    if (typeof bubble.text === 'string' && bubble.text.trim()) {
        return 'message';
    }
    return 'skip';
}
