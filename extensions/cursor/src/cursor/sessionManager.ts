import * as path from 'path';
import * as vscode from 'vscode';
import * as fs from 'fs';

import { RequestLedger } from "../interface";
import { findFolder } from '../utils';
import { captureException } from '../sentry';
import { executeQuery, executeQueryPaginated } from './sqlite';
import { orderBubbles } from './bubbleOrder';
import { buildSpans, buildTurnOutput, DEFAULT_SPAN_OPTIONS, SpanBuildOptions } from './spanBuilder';
import { prepareTraceForUpload, requestIdForTurn, requestKey, shouldProcessTrace } from './requestIdentity';

import { TraceData } from "../interface";

function readSpanOptions(): SpanBuildOptions | null {
    const config = vscode.workspace.getConfiguration();
    if (!config.get<boolean>('opik.detailedSpans.enabled', true)) {
        return null;
    }
    return {
        maxPayloadChars: config.get<number>(
            'opik.detailedSpans.maxPayloadChars',
            DEFAULT_SPAN_OPTIONS.maxPayloadChars
        ),
        maxSpansPerTurn: config.get<number>(
            'opik.detailedSpans.maxSpansPerTurn',
            DEFAULT_SPAN_OPTIONS.maxSpansPerTurn
        ),
    };
}

/**
 * Convert Cursor conversations to Opik traces using requestId as the durable
 * identity. Normal polling excludes previously unseen turns from before the
 * automatic tracking cutoff; the manual import command includes them.
 * 
 * @param conversations Array of conversation objects from cursor database
 * @param opikProjectName Project name for Opik
 * @returns Object containing traces and updated session info
 */
async function convertConversationsToTraces(
    conversations: any[],
    opikProjectName: string,
    requestLedger: RequestLedger,
    includeHistorical: boolean,
    automaticCutoffAt: number
) {
    const tracesData: TraceData[] = [];
    const updatedSessionInfo: Record<string, { lastMessageId?: string; lastMessageTime?: number }> = {};

    // Get git info once for all traces (performance optimization)
    const gitInfo = await getGitInfo();
    // A fork is created after its source composer. Processing oldest composers
    // first makes the original request the canonical cost owner when both are
    // first discovered in the same polling cycle.
    const orderedConversations = [...conversations].sort((left, right) =>
        Number(left.createdAt ?? 0) - Number(right.createdAt ?? 0)
    );

    for (const conversation of orderedConversations) {
        if (!conversation.bubbles || !Array.isArray(conversation.bubbles) || conversation.bubbles.length === 0) {
            console.log(`⏭️  Skipping composer ${conversation.composerId} - no bubbles`);
            continue;
        }

        const composerId = conversation.composerId;
        const sessionId = `cursor-composer-${composerId}`;
        console.log(`📤 Reconciling composer ${composerId} against the request ledger`);
        
        const conversationTraces = processConversationBubbles(
            conversation, 
            opikProjectName, 
            gitInfo,
            requestLedger,
            includeHistorical,
            automaticCutoffAt
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
    gitInfo: { branch?: string; commit?: string; remote?: string; repoName?: string } | null,
    requestLedger: RequestLedger,
    includeHistorical: boolean,
    automaticCutoffAt: number
) {
    const traces: TraceData[] = [];
    let lastMessageId: string | undefined = undefined;
    let lastMessageTime: number | undefined = undefined;

    // Initialize sequential timestamp starting from conversation createdAt
    let currentTimestamp = conversation.createdAt || Date.now();
    const TIMESTAMP_INCREMENT = 1000; // 1 second in milliseconds

    // First pass: assign sequential timestamps to all bubbles
    const bubblesWithTimestamps = conversation.bubbles.map((bubble: any, index: number) => {
        // Modern Cursor bubbles carry no timingInfo or timestamp, only an ISO
        // createdAt. Without it every timestamp below is fabricated from
        // conversation.createdAt, which makes trace times and usage attribution
        // wrong, so createdAt is the primary source here.
        const createdAtMs = bubble.createdAt ? Date.parse(bubble.createdAt) : NaN;
        const actualTime = bubble.timingInfo?.clientEndTime || 
                          bubble.timingInfo?.clientSettleTime ||
                          bubble.timingInfo?.clientRpcSendTime ||
                          bubble.timestamp ||
                          (Number.isNaN(createdAtMs) ? undefined : createdAtMs);
        
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
    const turnStartsMs = bubbleGroups
        .filter(group => group.userMessages.length > 0)
        .map(group => group.userMessages[0].resolvedTimestamp as number);
    for (const group of bubbleGroups) {
        // Always update lastMessageId to track our processing position
        const lastMessage = group.aiMessages.length > 0 
            ? group.aiMessages[group.aiMessages.length - 1]
            : group.userMessages[group.userMessages.length - 1];
        
        lastMessageId = lastMessage.id;
        lastMessageTime = lastMessage.resolvedTimestamp;

        // Only upload complete conversations (both user and AI messages)
        if (group.userMessages.length > 0 && group.aiMessages.length > 0) {
            const trace = createTraceFromBubbleGroup(
                group,
                conversation,
                opikProjectName,
                gitInfo,
                turnStartsMs
            );
            if (trace) {
                if (!shouldProcessTrace(trace, requestLedger, includeHistorical, automaticCutoffAt)) {
                    continue;
                }
                const prepared = prepareTraceForUpload(trace, requestLedger);
                if (prepared) {
                    traces.push(prepared);
                }
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
async function readCursorChatDataAsync(
    stateDbPath: string,
    lastSyncedAt: number,
    currentSyncTime: number,
    includeHistorical: boolean
): Promise<any> {
    // Use the database directly with -readonly flag (no expensive copy operation)
    // The sqlite3 binary with -readonly flag is safe and handles locks gracefully
    
    try {
        // Use the original database path directly
        const dbPath = stateDbPath;
        const fiveMinutesAgo = Date.now() - (5 * 60 * 1000);
        const lastSyncedAtWithBuffer = lastSyncedAt - (5 * 60 * 1000);

        // Find all composer chats updated between last sync and current sync time
        // This prevents race conditions by using a consistent time window
        // Using > (not >=) to avoid duplicates, and <= (not <) to avoid gaps
        // Prefix ranges instead of LIKE: LIKE is case-insensitive so SQLite cannot
        // use the index on key and scans the whole table, including the hundreds of
        // megabytes of agentKv and bubble rows.
        const timeFilter = includeHistorical
            ? ''
            : `AND json_extract(value, '$.lastUpdatedAt') > ${lastSyncedAtWithBuffer}
                AND json_extract(value, '$.lastUpdatedAt') <= ${currentSyncTime}`;
        const composerQuery = `SELECT key, value FROM cursorDiskKV 
                WHERE key >= 'composerData' AND key < 'composerDatb'
                ${timeFilter}
                AND (json_extract(value, '$.status') = 'completed' 
                     OR (json_extract(value, '$.status') != 'completed' 
                         AND json_extract(value, '$.lastUpdatedAt') < ${fiveMinutesAgo}))`;
        
        const composerRows = await executeQuery(dbPath, composerQuery);
        
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
            WHERE ${composerIds.map((id: string) => `(key >= 'bubbleId:${id}:' AND key < 'bubbleId:${id};')`).join(' OR ')}
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
                
                const orderedBubbles = orderBubbles(bubbles, composerData.fullConversationHeadersOnly);

                // Add conversation
                conversations.push({
                    chatTitle: composerData.name || `Composer Session ${index + 1}`,
                    bubbles: orderedBubbles,
                    lastSendTime: composerData.lastUpdatedAt || composerData.createdAt,
                    composerId: threadId,
                    createdAt: composerData.createdAt,
                    model: composerData.modelConfig?.modelName,
                    bubbleCount: orderedBubbles.length,
                    unifiedMode: composerData.unifiedMode,
                    isAgentic: composerData.isAgentic,
                    createdOnBranch: composerData.createdOnBranch,
                    contextTokensUsed: composerData.contextTokensUsed,
                    contextTokenLimit: composerData.contextTokenLimit,
                    filesChangedCount: composerData.filesChangedCount,
                    totalLinesAdded: composerData.totalLinesAdded,
                    totalLinesRemoved: composerData.totalLinesRemoved
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

export function resolveStateDbPath(VSInstallationPath: string): string | null {
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

    return path.join(globalStoragePaths[0], 'state.vscdb');
}

export async function findAndReturnNewTraces(
    context: vscode.ExtensionContext, 
    VSInstallationPath: string, 
    requestLedger: RequestLedger,
    lastSyncedAt: number,
    currentSyncTime: number,
    includeHistorical: boolean,
    automaticCutoffAt: number
) {
    const opikProjectName: string = vscode.workspace.getConfiguration().get('opik.projectName') || 'default';

    const stateDbPath = resolveStateDbPath(VSInstallationPath);
    if (!stateDbPath) {
        return null;
    }

    if (!fs.existsSync(stateDbPath)) {
        const error = new Error(`Could not find global SQLite state DB at path: ${stateDbPath}`);
        captureException(error);
        console.warn("Could not find global SQLite state DB.")
        return null;
    } else {
        try {
            const conversations = await readCursorChatDataAsync(
                stateDbPath,
                lastSyncedAt,
                currentSyncTime,
                includeHistorical
            );
            
            if (conversations && Array.isArray(conversations) && conversations.length > 0) {
                // Convert conversations to Opik traces with per-session tracking
                const result = await convertConversationsToTraces(
                    conversations,
                    opikProjectName,
                    requestLedger,
                    includeHistorical,
                    automaticCutoffAt
                );
                
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
export function groupBubblesByType(bubbles: any[]) {
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

// Helper function to create a trace from a bubble group
function createTraceFromBubbleGroup(
    group: { userMessages: any[], aiMessages: any[] },
    conversation: any,
    opikProjectName: string,
    gitInfo: { branch?: string; commit?: string; remote?: string; repoName?: string } | null,
    turnStartsMs: number[]
): TraceData | null {
    const { userMessages, aiMessages } = group;
    
    // Extract user content inline
    const userContent = userMessages
        .map(msg => msg.text || msg.content || msg.rawText || '')
        .filter(content => content.trim())
        .join('\n\n');
    
    // The messages the assistant wrote, with the name of every tool call in
    // between. A turn that is only tool calls still produces an output.
    const assistantContent = buildTurnOutput(aiMessages);

    const spanOptions = readSpanOptions();
    const spans = spanOptions ? buildSpans(group, conversation, spanOptions) : [];

    if (!userContent || !assistantContent) {
        return null;
    }

    // Extract timestamp from the resolved timestamps
    const firstUserMessage = userMessages[0];
    const lastAiMessage = aiMessages[aiMessages.length - 1];
    
    const startTime = firstUserMessage.resolvedTimestamp || conversation.createdAt || Date.now();
    const endTime = lastAiMessage.resolvedTimestamp || startTime + 1000; // Add 1 second if no end time

    // The raw bubbles used to be copied here. The child spans now hold that
    // data in a readable form, and the copy was the largest part of the payload.
    const metadata = {
        conversationTitle: conversation.chatTitle,
        composerId: conversation.composerId,
        totalBubbles: conversation.bubbleCount,
        conversationCreatedAt: conversation.createdAt,
        mode: conversation.unifiedMode,
        isAgentic: conversation.isAgentic,
        createdOnBranch: conversation.createdOnBranch,
        contextTokensUsed: conversation.contextTokensUsed,
        contextTokenLimit: conversation.contextTokenLimit,
        filesChangedCount: conversation.filesChangedCount,
        totalLinesAdded: conversation.totalLinesAdded,
        totalLinesRemoved: conversation.totalLinesRemoved,
        gitInfo: gitInfo
    };

    // Create git-based tags only for recent conversations (last 2 minutes)
    const TWO_MINUTES_MS = 2 * 60 * 1000; // 2 minutes in milliseconds
    const conversationAge = Date.now() - (conversation.createdAt || 0);
    const isRecentConversation = conversationAge <= TWO_MINUTES_MS;
    
    const tags: string[] = [];
    if (isRecentConversation && gitInfo) {
        // Apply current git context for recent conversations
        if (gitInfo.branch) {
            tags.push(gitInfo.branch);
        }
        if (gitInfo.repoName) {
            tags.push(`repo:${gitInfo.repoName}`);
        }
        if (gitInfo.commit) {
            tags.push(`commit:${gitInfo.commit}`);
        }
        tags.push("recent");
    } else {
        // Mark older conversations as historical
        tags.push("historical");
    }

    const requestId = requestIdForTurn(group);
    return {
        id: '',
        root_span_id: '',
        request_id: requestId,
        request_key: requestKey(opikProjectName, requestId),
        upload_kind: 'canonical',
        revision: 1,
        usage_key: '',
        cost_owner: true,
        name: "cursor-chat",
        project_name: opikProjectName,
        start_time: new Date(startTime).toISOString(),
        end_time: new Date(endTime).toISOString(),
        turn_start_ms: startTime,
        turn_starts_ms: turnStartsMs,
        model: conversation.model,
        input: { input: userContent },
        output: { output: assistantContent },
        thread_id: conversation.composerId,
        tags: tags,
        metadata: metadata,
        spans: spans
    };
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
