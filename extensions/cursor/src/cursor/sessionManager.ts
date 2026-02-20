import * as path from 'path';
import * as vscode from 'vscode';
import * as fs from 'fs';

import { SessionInfo } from "../interface";
import { findFolder } from '../utils';
import { captureException } from '../sentry';
import { executeQuery, executeQueryPaginated } from './sqlite';

import { TraceData, SpanData } from "../interface";
import { debugLog } from '../utils';

/**
 * Convert cursor conversations to Opik traces with per-session tracking.
 * Each composer session maintains its own progress tracking to avoid duplicate uploads.
 * 
 * Strategy:
 *   - Never synced conversations: Upload entire conversation
 *   - Already synced, no new messages: Skip (avoid duplicates)
 *   - Already synced, has new messages: Upload new messages only
 * 
 * @param conversations Array of conversation objects from cursor database
 * @param opikProjectName Project name for Opik
 * @param sessionInfo Existing session info with per-composer tracking
 * @returns Object containing traces and updated session info
 */
async function convertConversationsToTraces(conversations: any[], opikProjectName: string, sessionInfo: Record<string, SessionInfo>) {
    const tracesData: TraceData[] = [];
    const updatedSessionInfo: Record<string, { lastMessageId?: string; lastMessageTime?: number }> = {};

    // Get git info once for all traces (performance optimization)
    const gitInfo = await getGitInfo();

    for (const conversation of conversations) {
        if (!conversation.bubbles || !Array.isArray(conversation.bubbles) || conversation.bubbles.length === 0) {
            console.log(`⏭️  Skipping composer ${conversation.composerId} - no bubbles`);
            continue;
        }

        const composerId = conversation.composerId;
        const sessionId = `cursor-composer-${composerId}`;
        const lastUploadId = sessionInfo[sessionId]?.lastUploadId;
        
        // Check if there are new messages
        const latestMessage = conversation.bubbles[conversation.bubbles.length - 1];
        const neverSynced = !lastUploadId;
        const hasNewMessagesSinceSync = lastUploadId && latestMessage && latestMessage.id !== lastUploadId;
        
        // Skip if already synced and no new messages
        if (!neverSynced && !hasNewMessagesSinceSync) {
            console.log(`⏭️  Skipping composer ${composerId} - no new messages (latest: ${latestMessage?.id}, last uploaded: ${lastUploadId})`);
            continue; // Skip - no new messages and already synced
        }

        // Determine processing strategy:
        // - If never synced (no lastUploadId): upload entire conversation
        // - If previously synced: upload only new messages after lastUploadId
        const uploadEntireConversation = lastUploadId === undefined;
        
        if (uploadEntireConversation) {
            console.log(`📤 Processing entire conversation for composer ${composerId} (never synced)`);
        } else {
            console.log(`📤 Processing new messages for composer ${composerId} after message ${lastUploadId}`);
        }
        
        const conversationTraces = processConversationBubbles(
            conversation, 
            opikProjectName, 
            uploadEntireConversation,
            lastUploadId,
            gitInfo // Pass git info to bubble processing
        );
        
        tracesData.push(...conversationTraces.traces);
        
        // Track the last processed message for this composer session
        if (conversationTraces.lastMessageId) {
            updatedSessionInfo[sessionId] = {
                lastMessageId: conversationTraces.lastMessageId,
                lastMessageTime: conversationTraces.lastMessageTime
            };
        }
    }
    
    console.log(`✅ Generated ${tracesData.length} traces across ${Object.keys(updatedSessionInfo).length} active sessions`);
    return { tracesData, updatedSessionInfo };
}

// Helper function to process bubbles in a conversation
function processConversationBubbles(
    conversation: any, 
    opikProjectName: string, 
    uploadEntireConversation: boolean, 
    lastUploadId: string | undefined,
    gitInfo: { branch?: string; commit?: string; remote?: string; repoName?: string } | null
) {
    const traces: TraceData[] = [];
    let lastMessageId: string | undefined = undefined;
    let lastMessageTime: number | undefined = undefined;
    let shouldAppend = uploadEntireConversation;

    // Initialize sequential timestamp starting from conversation createdAt
    let currentTimestamp = conversation.createdAt || Date.now();
    const TIMESTAMP_INCREMENT = 1000; // 1 second in milliseconds

    // First pass: assign sequential timestamps to all bubbles
    const bubblesWithTimestamps = conversation.bubbles.map((bubble: any, index: number) => {
        // Check if bubble has actual timing information
        const actualTime = bubble.timingInfo?.clientEndTime || 
                          bubble.timingInfo?.clientSettleTime ||
                          bubble.timingInfo?.clientRpcSendTime ||
                          bubble.timestamp;
        
        if (actualTime) {
            currentTimestamp = actualTime;
        } else {
            // Increment by 1 second for bubbles without timing info
            currentTimestamp += TIMESTAMP_INCREMENT;
        }
        
        return {
            ...bubble,
            resolvedTimestamp: currentTimestamp
        };
    });

    const bubbleGroups = groupBubblesByType(bubblesWithTimestamps);

    for (const group of bubbleGroups) {
        // Always update lastMessageId to track our processing position
        const lastMessage = group.aiMessages.length > 0 
            ? group.aiMessages[group.aiMessages.length - 1]
            : group.userMessages[group.userMessages.length - 1];
        
        lastMessageId = lastMessage.id;
        lastMessageTime = lastMessage.resolvedTimestamp;

        // If we're uploading the entire conversation, process all groups
        // If we're uploading incrementally, find where to start from lastUploadId
        if (!shouldAppend) {
            // Check both user and AI messages for the lastUploadId
            const hasTargetMessage = [...group.userMessages, ...group.aiMessages]
                .some(msg => msg.id === lastUploadId);
            
            if (hasTargetMessage) {
                shouldAppend = true;
                continue; // Skip this group since it was already processed
            }
        }

        if (!shouldAppend) {
            continue; // Still haven't reached the point where we left off
        }

        // Only upload complete conversations (both user and AI messages)
        if (group.userMessages.length > 0 && group.aiMessages.length > 0) {
            const trace = createTraceFromBubbleGroup(group, conversation, opikProjectName, gitInfo);
            if (trace) {
                traces.push(trace);
            }
        }
        // Note: We still track incomplete conversations but don't upload them
        // This ensures we don't miss them in the next round when AI responds
    }

    return { traces, lastMessageId, lastMessageTime };
}

/**
 * Read cursor chat data from SQLite database (asynchronous version)
 */
async function readCursorChatDataAsync(stateDbPath: string, lastSyncedAt: number, currentSyncTime: number): Promise<any> {
    // Use the database directly with -readonly flag (no expensive copy operation)
    // The sqlite3 binary with -readonly flag is safe and handles locks gracefully
    
    try {
        // Use the original database path directly
        const dbPath = stateDbPath;
        const fiveMinutesAgo = Date.now() - (10 * 1000);
        const lastSyncedAtWithBuffer = lastSyncedAt - (5 * 60 * 1000);

        // Find all composer chats updated between last sync and current sync time
        // This prevents race conditions by using a consistent time window
        // Using > (not >=) to avoid duplicates, and <= (not <) to avoid gaps
        const composerQuery = `SELECT key, value FROM cursorDiskKV 
                WHERE key LIKE 'composerData%' 
                AND json_extract(value, '$.lastUpdatedAt') > ${lastSyncedAtWithBuffer}
                AND json_extract(value, '$.lastUpdatedAt') <= ${currentSyncTime}
                AND (json_extract(value, '$.status') = 'completed' 
                     OR (json_extract(value, '$.status') != 'completed' 
                         AND json_extract(value, '$.lastUpdatedAt') < ${fiveMinutesAgo}))`;
        
        const composerRows = await executeQuery(dbPath, composerQuery);

        // Explore DB key prefixes for discovery
        try {
            const prefixQuery = `SELECT SUBSTR(key, 1, INSTR(key || ':', ':') - 1) as prefix, COUNT(*) as cnt FROM cursorDiskKV GROUP BY prefix ORDER BY cnt DESC LIMIT 30`;
            const prefixes = await executeQuery(dbPath, prefixQuery);
            debugLog('DB key prefixes', prefixes);
        } catch (e) { /* ignore */ }

        if (!composerRows || composerRows.length === 0) {
            console.log(`⚠️ No composer data found (queried ${lastSyncedAt} < lastUpdatedAt <= ${currentSyncTime})`);
            return [];
        }

        console.log(`📊 Found ${composerRows.length} composer(s) updated since last sync (${lastSyncedAt} < lastUpdatedAt <= ${currentSyncTime})`);
        
        // Log the composer IDs and their update times for debugging
        composerRows.forEach((row: any) => {
            try {
                const composerData = JSON.parse(row.value);
                const composerId = row.key.split(':')[1];
                console.log(`  → Composer ${composerId}: updated at ${composerData.lastUpdatedAt}, status: ${composerData.status}`);
            } catch (e) {
                // Ignore parse errors
            }
        });
        
        // Extract composer IDs from the keys (format: composerData:<composerId>)
        const composerIds = composerRows
            .map((row: any) => {
                if (typeof row.key === 'string') {
                    return row.key.split(':')[1];
                }
                return null;
            })
            .filter((id: string | null) => id !== null);
        
        if (composerIds.length === 0) {
            console.log(`⚠️ No valid composer IDs found`);
            return [];
        }
        
        console.log(`🔍 Fetching bubbles for ${composerIds.length} active composer(s)`);
        
        // Build optimized query to only fetch bubbles for relevant composers
        // Bubble key format: bubbleId:<composerId>:<bubbleId>
        // This dramatically reduces data transfer by filtering at the database level
        const bubbleQuery = `
            SELECT key, value FROM cursorDiskKV 
            WHERE ${composerIds.map((id: string) => `key LIKE 'bubbleId:${id}:%'`).join(' OR ')}
        `;
        
        const allBubbleRows = await executeQueryPaginated(dbPath, bubbleQuery, 100);
        console.log(`✅ Retrieved ${allBubbleRows.length} bubbles (only for active composers)`);
        
        // Group bubbles by composer ID
        const bubblesByComposer: Record<string, any[]> = {};
        
        allBubbleRows.forEach((bubbleRow: any) => {
            if (!bubbleRow.value) return;
            
            try {
                const key = bubbleRow.key;
                if (typeof key !== 'string') return;
                const composerId = key.split(':')[1];
                const value = bubbleRow.value;
                if (typeof value !== 'string') return;
                const chatData = JSON.parse(value);
                
                if (!chatData) return;
                
                if (!bubblesByComposer[composerId]) {
                    bubblesByComposer[composerId] = [];
                }
                
                // Structure the bubble data
                const bubble = {
                    ...chatData,
                    id: key.split(':')[2], // Extract bubble ID
                    type: chatData.type === 1 ? 'user' : chatData.type === 2 ? 'ai' : 'unknown',
                    text: chatData.text || chatData.content || '',
                    content: chatData.text || chatData.content || '',
                    rawText: chatData.text || chatData.content || '',
                    richText: chatData.richText || '',
                };
                bubblesByComposer[composerId].push(bubble);
            } catch (parseErr) {
                // Silently skip unparseable chat data
            }
        });
        
        // Process each composer and build conversations
        const conversations: any[] = [];
        
        composerRows.forEach((composerRow: any, index: number) => {
            try {
                const value = composerRow.value;
                if (typeof value !== 'string') return;
                const composerData = JSON.parse(value);
                
                // Handle null composerData
                if (!composerData) {
                    console.log(`Skipping null composer data for row ${index}`);
                    return;
                }
                
                const key = composerRow.key;
                if (typeof key !== 'string') return;
                const threadId = key.split(':')[1];
                
                // Get bubbles for this composer
                const bubbles = bubblesByComposer[threadId] || [];
                
                // Sort bubbles using fullConversationHeadersOnly order
                if (composerData.fullConversationHeadersOnly && Array.isArray(composerData.fullConversationHeadersOnly)) {
                    // Create a map of bubbleId to order index
                    const orderMap = new Map();
                    composerData.fullConversationHeadersOnly.forEach((header: any, index: number) => {
                        if (header.bubbleId) {
                            orderMap.set(header.bubbleId, index);
                        }
                    });
                    
                    // Sort bubbles according to the order in fullConversationHeadersOnly
                    bubbles.sort((a, b) => {
                        const aOrder = orderMap.get(a.id) ?? Number.MAX_SAFE_INTEGER;
                        const bOrder = orderMap.get(b.id) ?? Number.MAX_SAFE_INTEGER;
                        return aOrder - bOrder;
                    });
                }
                
                conversations.push({
                    chatTitle: composerData.name || `Composer Session ${index + 1}`,
                    bubbles: bubbles,
                    lastSendTime: composerData.lastUpdatedAt || composerData.createdAt,
                    composerId: threadId,
                    createdAt: composerData.createdAt,
                    bubbleCount: bubbles.length,
                    modelName: composerData.modelConfig?.modelName,
                    usageData: composerData.usageData,
                    contextTokensUsed: composerData.contextTokensUsed,
                    contextTokenLimit: composerData.contextTokenLimit,
                    isAgentic: composerData.isAgentic,
                    unifiedMode: composerData.unifiedMode,
                    agentBackend: composerData.agentBackend,
                    status: composerData.status,
                    createdOnBranch: composerData.createdOnBranch,
                });
            } catch (parseErr) {
                captureException(parseErr);
                console.error(`Could not parse composer data for row ${index}:`, parseErr);
            }
        });
        
        return conversations;
    } catch (error) {
        captureException(error);
        console.error(`Error reading database ${stateDbPath}:`, error);
        throw error;
    }
}

/**
 * Find all state.vscdb files in the given globalStorage directories
 */

export async function findAndReturnNewTraces(
    context: vscode.ExtensionContext, 
    VSInstallationPath: string, 
    sessionInfo: Record<string, SessionInfo>,
    lastSyncedAt: number,
    currentSyncTime: number
) {
    const opikProjectName: string = vscode.workspace.getConfiguration().get('opik.projectName') || 'default';
    
    const globalStoragePaths = findFolder(VSInstallationPath, 'globalStorage');
    
    if (globalStoragePaths.length > 1) {
        const error = new Error(`More than one global storage folder found - Should not happen - ${globalStoragePaths}`);
        captureException(error);
        console.warn(`More than one global storage folder found - Should not happen - ${globalStoragePaths}`)
    }
    
    if (globalStoragePaths.length === 0) {
        const error = new Error("Could not find global SQLite state DB.");
        captureException(error);
        console.warn("Could not find global SQLite state DB.")
        return null;
    }
    
    // Look for state.vscdb file inside the globalStorage directory
    const globalStoragePath = globalStoragePaths[0];
    const stateDbPath = path.join(globalStoragePath, 'state.vscdb');
    
    if (!fs.existsSync(stateDbPath)) {
        const error = new Error(`Could not find global SQLite state DB at path: ${stateDbPath}`);
        captureException(error);
        console.warn("Could not find global SQLite state DB.")
        return null;
    } else {
        try {
            const conversations = await readCursorChatDataAsync(stateDbPath, lastSyncedAt, currentSyncTime);
            
            if (conversations && Array.isArray(conversations) && conversations.length > 0) {
                // Convert conversations to Opik traces with per-session tracking
                const result = await convertConversationsToTraces(conversations, opikProjectName, sessionInfo);
                
                return {
                    tracesData: result.tracesData,
                    updatedSessionInfo: result.updatedSessionInfo
                };
            }
            
            // Log to Sentry when conversations are found but no traces generated
            if (conversations.length > 0) {
                const error = new Error(`Found ${conversations.length} conversations but generated 0 traces`);
                captureException(error);
            }
            return { tracesData: [], updatedSessionInfo: {} };
        } catch (error) {
            captureException(error);
            console.error("Error reading cursor chat data:", error);
            return null;
        }
    }
}

// Helper function to group bubbles by conversation turns
function groupBubblesByType(bubbles: any[]) {
    const groups: { userMessages: any[], aiMessages: any[] }[] = [];
    let currentGroup: { userMessages: any[], aiMessages: any[] } | null = null;

    for (let i = 0; i < bubbles.length; i++) {
        const bubble = bubbles[i];

        if (bubble.type === 'user') {
            // User message: start a new group if we already have one with content
            if (currentGroup && (currentGroup.userMessages.length > 0 || currentGroup.aiMessages.length > 0)) {
                groups.push(currentGroup);
            }
            
            // Start new group with this user message
            currentGroup = {
                userMessages: [bubble],
                aiMessages: []
            };
        } else if (bubble.type === 'ai' && currentGroup) {
            // AI message: append to current group
            currentGroup.aiMessages.push(bubble);
        }
    }

    // Don't forget to add the last group
    if (currentGroup && (currentGroup.userMessages.length > 0 || currentGroup.aiMessages.length > 0)) {
        groups.push(currentGroup);
    }

    return groups;
}

function parseThinkingBlocks(text: string): { thinkingSpans: SpanData[]; cleanedText: string } {
    const thinkingSpans: SpanData[] = [];
    const thoughtRegex = /⛢Thought☤([\s\S]*?)⛢\/Thought☤/g;
    let match;
    let count = 0;
    while ((match = thoughtRegex.exec(text)) !== null) {
        count++;
        thinkingSpans.push({
            name: 'Thinking',
            type: 'llm',
            input: {},
            output: { thinking: match[1].trim() },
            tags: ['cursor', 'thinking'],
        });
    }
    const cleanedText = text
        .replace(/⛢Thought☤[\s\S]*?⛢\/Thought☤/g, '')
        .replace(/⛢Action☤[\s\S]*?⛢\/Action☤/g, '')
        .replace(/⛢RawAction☤[\s\S]*?⛢\/RawAction☤/g, '')
        .trim();
    return { thinkingSpans, cleanedText };
}

function parseToolSpans(bubble: any): SpanData[] {
    if (!bubble.toolFormerData) return [];
    const tools = Array.isArray(bubble.toolFormerData) ? bubble.toolFormerData : [bubble.toolFormerData];
    return tools.map((tool: any, i: number) => {
        let input = {};
        try { input = tool.params ? JSON.parse(tool.params) : (tool.rawArgs ? JSON.parse(tool.rawArgs) : {}); } catch { input = tool.params || tool.rawArgs || {}; }
        let output: any;
        try { output = tool.result ? { result: JSON.parse(tool.result) } : undefined; } catch { output = tool.result != null ? { result: tool.result } : undefined; }
        return {
            name: tool.name || `tool_${i}`,
            type: 'tool' as const,
            input,
            output,
            metadata: {
                tool_call_id: tool.toolCallId,
                tool_name: tool.name,
                status: tool.status,
                message_type: 'tool_use',
            },
            tags: ['cursor', 'tool_use', tool.name].filter(Boolean),
        };
    });
}

// Helper function to create a trace from a bubble group
function createTraceFromBubbleGroup(
    group: { userMessages: any[], aiMessages: any[] },
    conversation: any,
    opikProjectName: string,
    gitInfo: { branch?: string; commit?: string; remote?: string; repoName?: string } | null
): TraceData | null {
    const { userMessages, aiMessages } = group;
    
    const userContent = userMessages
        .map(msg => msg.text || msg.content || msg.rawText || '')
        .filter(content => content.trim())
        .join('\n\n');

    const spans: SpanData[] = [];
    const cleanedParts: string[] = [];

    for (const msg of aiMessages) {
        const text = msg.text || msg.content || msg.rawText || '';
        const bubbleTime = msg.createdAt ? new Date(msg.createdAt).toISOString() : undefined;

        debugLog(`AI bubble: capabilityType=${msg.capabilityType}, thinking=${!!msg.thinking}, tool=${msg.toolFormerData?.name || 'none'}, tokens=${msg.tokenCount?.inputTokens || 0}/${msg.tokenCount?.outputTokens || 0}`);

        // Per-message usage: apply only to first span from this message (dedup like Claude plugin)
        const msgUsage = msg.tokenCount && (msg.tokenCount.inputTokens || msg.tokenCount.outputTokens)
            ? {
                prompt_tokens: msg.tokenCount.inputTokens || 0,
                completion_tokens: msg.tokenCount.outputTokens || 0,
                total_tokens: (msg.tokenCount.inputTokens || 0) + (msg.tokenCount.outputTokens || 0),
            }
            : undefined;
        let usageApplied = false;

        // 1. Check for `thinking` field (structured thinking from Cursor)
        if (msg.thinking && msg.thinking.text) {
            const thinkingEndTime = msg.thinkingDurationMs && bubbleTime
                ? new Date(new Date(msg.createdAt).getTime() + msg.thinkingDurationMs).toISOString()
                : bubbleTime;
            spans.push({
                name: 'Thinking',
                type: 'llm',
                input: {},
                output: { thinking: msg.thinking.text },
                start_time: bubbleTime,
                end_time: thinkingEndTime,
                usage: !usageApplied ? msgUsage : undefined,
                model: conversation.modelName,
                provider: 'cursor',
                tags: ['cursor', 'thinking'],
            });
            if (msgUsage) usageApplied = true;
        }

        // 2. Check allThinkingBlocks as fallback
        if (!msg.thinking && msg.allThinkingBlocks && Array.isArray(msg.allThinkingBlocks) && msg.allThinkingBlocks.length > 0) {
            msg.allThinkingBlocks.forEach((block: any) => {
                spans.push({
                    name: 'Thinking',
                    type: 'llm',
                    input: {},
                    output: { thinking: block.thinking || block.content || block },
                    start_time: bubbleTime,
                    end_time: bubbleTime,
                    usage: !usageApplied ? msgUsage : undefined,
                    model: conversation.modelName,
                    provider: 'cursor',
                    tags: ['cursor', 'thinking'],
                });
                if (msgUsage) usageApplied = true;
            });
        }

        // 3. Regex fallback for ⛢Thought☤ markup
        const { thinkingSpans, cleanedText } = parseThinkingBlocks(text);
        if (!msg.thinking && (!msg.allThinkingBlocks || !Array.isArray(msg.allThinkingBlocks) || msg.allThinkingBlocks.length === 0)) {
            for (const ts of thinkingSpans) {
                ts.input = {};
                ts.start_time = bubbleTime;
                ts.end_time = bubbleTime;
                ts.model = conversation.modelName;
                ts.provider = 'cursor';
                if (!usageApplied && msgUsage) {
                    ts.usage = msgUsage;
                    usageApplied = true;
                }
                spans.push(ts);
            }
        }

        // 4. Tool spans
        const toolSpans = parseToolSpans(msg);
        for (const ts of toolSpans) {
            ts.start_time = bubbleTime;
            ts.end_time = bubbleTime;
            if (!usageApplied && msgUsage) {
                ts.usage = msgUsage;
                usageApplied = true;
            }
            spans.push(ts);
        }

        // 5. Text span
        if (cleanedText) {
            cleanedParts.push(cleanedText);
            spans.push({
                name: 'Text',
                type: 'general',
                input: {},
                output: { text: cleanedText },
                start_time: bubbleTime,
                end_time: bubbleTime,
                usage: !usageApplied ? msgUsage : undefined,
                model: conversation.modelName,
                provider: 'cursor',
                tags: ['cursor', 'assistant_response'],
            });
            if (msgUsage) usageApplied = true;
        }
    }

    const assistantContent = cleanedParts.join('\n\n');

    if (!userContent || !assistantContent) {
        return null;
    }

    // Calculate token usage from AI messages
    let totalPromptTokens = 0;
    let totalCompletionTokens = 0;
    let hasTokenCounts = false;
    
    aiMessages.forEach(msg => {
        if (msg.tokenCount && (msg.tokenCount.inputTokens || msg.tokenCount.outputTokens)) {
            totalPromptTokens += msg.tokenCount.inputTokens || 0;
            totalCompletionTokens += msg.tokenCount.outputTokens || 0;
            hasTokenCounts = true;
        }
    });
    
    const usage = hasTokenCounts ? {
        prompt_tokens: totalPromptTokens,
        completion_tokens: totalCompletionTokens,
        total_tokens: totalPromptTokens + totalCompletionTokens
    } : undefined;

    const firstUserMessage = userMessages[0];
    const lastAiMessage = aiMessages[aiMessages.length - 1];

    // Prefer createdAt (ISO string) for accurate timestamps, fall back to resolvedTimestamp
    const startTime = firstUserMessage.createdAt
        ? new Date(firstUserMessage.createdAt).getTime()
        : (firstUserMessage.resolvedTimestamp || conversation.createdAt || Date.now());
    const endTime = lastAiMessage.createdAt
        ? new Date(lastAiMessage.createdAt).getTime()
        : (lastAiMessage.resolvedTimestamp || startTime + 1000);

    // Compute cost from usageData
    let totalCostCents = 0;
    let totalRequests = 0;
    if (conversation.usageData && typeof conversation.usageData === 'object') {
        for (const modelKey of Object.keys(conversation.usageData)) {
            const entry = conversation.usageData[modelKey];
            if (entry?.costInCents) totalCostCents += entry.costInCents;
            if (entry?.amount) totalRequests += entry.amount;
        }
    }

    const metadata: any = {
        conversationTitle: conversation.chatTitle,
        composerId: conversation.composerId,
        model: conversation.modelName,
        totalBubbles: conversation.bubbleCount,
        conversationCreatedAt: conversation.createdAt,
        gitInfo: gitInfo,
        cursor: {
            isAgentic: conversation.isAgentic,
            unifiedMode: conversation.unifiedMode,
            agentBackend: conversation.agentBackend,
            status: conversation.status,
            createdOnBranch: conversation.createdOnBranch,
        },
    };

    if (totalCostCents > 0) {
        metadata.cost = {
            totalCostCents,
            totalCostDollars: totalCostCents / 100,
            totalRequests,
            usageData: conversation.usageData,
        };
    }

    if (conversation.contextTokensUsed) {
        metadata.context = {
            tokensUsed: conversation.contextTokensUsed,
            tokenLimit: conversation.contextTokenLimit,
            utilizationPercent: conversation.contextTokenLimit
                ? Math.round((conversation.contextTokensUsed / conversation.contextTokenLimit) * 100)
                : undefined,
        };
    }

    const tags: string[] = ['cursor'];
    if (conversation.modelName) tags.push(conversation.modelName);
    if (conversation.isAgentic) tags.push('agentic');

    const conversationAge = Date.now() - (conversation.createdAt || 0);
    const isRecentConversation = conversationAge <= 2 * 60 * 1000;

    if (isRecentConversation && gitInfo) {
        if (gitInfo.branch) tags.push(gitInfo.branch);
        if (gitInfo.repoName) tags.push(`repo:${gitInfo.repoName}`);
        if (gitInfo.commit) tags.push(`commit:${gitInfo.commit}`);
        tags.push('recent');
    } else {
        tags.push('historical');
    }

    const traceData: any = {
        name: conversation.chatTitle || 'cursor-chat',
        project_name: opikProjectName,
        start_time: new Date(startTime).toISOString(),
        end_time: new Date(endTime).toISOString(),
        input: { input: userContent },
        output: { output: assistantContent },
        thread_id: conversation.composerId,
        tags: tags,
        metadata: metadata,
        model: conversation.modelName,
    };

    if (usage) {
        traceData.usage = usage;
    }

    if (spans.length > 0) {
        traceData.spans = spans;
    }

    return traceData;
}

/**
 * Get git information using VSCode's built-in Git API
 */
async function getGitInfo(): Promise<{ branch?: string; commit?: string; remote?: string; repoName?: string } | null> {
    try {
        // Get the git extension that's built into VSCode
        const gitExtension = vscode.extensions.getExtension('vscode.git')?.exports;
        const git = gitExtension?.getAPI(1);
        
        if (git && git.repositories.length > 0) {
            const repo = git.repositories[0]; // Primary repository
            
            // Get repository name from path
            const repoName = path.basename(repo.rootUri.fsPath);
            
            // Get remote URL and clean it up
            let remote = repo.state.remotes[0]?.fetchUrl;
            if (remote) {
                // Clean up git URLs to get just the repo identifier
                remote = remote.replace(/^https?:\/\//, '')
                              .replace(/^git@/, '')
                              .replace(/\.git$/, '')
                              .replace(/:/g, '/');
            }
            
            return {
                branch: repo.state.HEAD?.name,
                commit: repo.state.HEAD?.commit?.substring(0, 7), // Short commit hash
                remote: remote,
                repoName: repoName
            };
        }
        
        return null;
    } catch (error) {
        // This is expected to fail sometimes, so we only log as debug level
        console.log('Could not get git information:', error);
        return null;
    }
}
