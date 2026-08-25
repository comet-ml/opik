import * as vscode from 'vscode';
import { findAndReturnNewTraces, resolveStateDbPath } from './sessionManager';
import { applyTurnUsage, logTracesToOpik } from '../opik';
import { getSessionInfo, updateSessionInfo, getLastSyncedAt, updateLastSyncedAt } from '../state';
import { captureException } from '../sentry';
import { UsageEnricher } from './usage';

export class CursorService {
  private context: vscode.ExtensionContext;
  private isProcessing: boolean = false;
  private usageEnricher: UsageEnricher;
  private apiKey: string | undefined;

  constructor(context: vscode.ExtensionContext) {
    this.context = context;
    this.usageEnricher = new UsageEnricher(context, async (pending, usage) => {
      if (!this.apiKey) {
        throw new Error('No API key available to patch span usage');
      }
      await applyTurnUsage(this.apiKey, pending, usage);
    });
  }

  /**
   * Process cursor traces and log them to Opik
   */
  async processCursorTraces(apiKey: string, vsInstallationPath: string): Promise<number> {
    // Prevent concurrent processing to avoid duplicates
    if (this.isProcessing) {
      console.log('⏳ Cursor trace processing already in progress, skipping this cycle');
      return 0;
    }

    this.isProcessing = true;
    this.apiKey = apiKey;
    let numberOfTracesLogged = 0;
    let sessionInfo = getSessionInfo(this.context);
    const lastSyncedAt = getLastSyncedAt(this.context);
    // Capture current time before processing to avoid race conditions
    const currentSyncTime = Date.now();
    
    try {
      const cursorResult = await findAndReturnNewTraces(this.context, vsInstallationPath, sessionInfo, lastSyncedAt, currentSyncTime);
      
      if (cursorResult && cursorResult.tracesData) {
        const { tracesData, updatedSessionInfo } = cursorResult;
        
        // Validate trace data quality
        const invalidTraces = tracesData.filter(trace => 
          !trace.input?.input || 
          !trace.output?.output || 
          !trace.thread_id ||
          !trace.project_name
        );
        
        // Log to Sentry if we have invalid traces
        if (invalidTraces.length > 0) {
          const error = new Error(`Found ${invalidTraces.length} invalid traces out of ${tracesData.length} total traces`);
          captureException(error);
          console.warn(`⚠️ Found ${invalidTraces.length} invalid traces - these will be skipped`);
        }
        
        if (tracesData.length > 0) {
          console.log(`📤 Logging ${tracesData.length} cursor traces to Opik`);
          
          const loggedTurns = await logTracesToOpik(apiKey, tracesData);
          this.usageEnricher.track(loggedTurns);
          
          // Update session info for each composer session
          Object.entries(updatedSessionInfo).forEach(([sessionId, sessionData]) => {
            try {
              if (!sessionInfo[sessionId]) {
                sessionInfo[sessionId] = {};
              }

              if (sessionData.lastMessageId) {
                sessionInfo[sessionId].lastUploadId = sessionData.lastMessageId;
              }
              if (sessionData.lastMessageTime) {
                sessionInfo[sessionId].lastUploadTime = sessionData.lastMessageTime;
              }
            } catch (sessionError) {
              captureException(sessionError);
              console.error(`Error updating session ${sessionId}:`, sessionError);
              // Continue with other sessions even if one fails
            }
          });

          numberOfTracesLogged += tracesData.length;
          console.log(`✅ Successfully logged ${numberOfTracesLogged} cursor traces across ${Object.keys(updatedSessionInfo).length} composer sessions`);
        } else {
          // This is normal behavior when there are no new conversations
          console.log(`ℹ️ No new cursor traces to log`);
        }

        updateSessionInfo(this.context, sessionInfo);
        
        // Update lastSyncedAt after successful processing (even if no traces uploaded)
        // This prevents re-querying the same conversations
        updateLastSyncedAt(this.context, currentSyncTime);
      } else {
        const error = new Error("No cursor data returned from findAndReturnNewTraces");
        captureException(error);
        console.log(`⚠️ No cursor data returned`);
      }
    } catch (error) {
      const errorContext = {
        operation: 'process_cursor_traces',
        hasApiKey: !!apiKey,
        installationPath: !!vsInstallationPath
      };

      captureException(error);
      console.error('Error processing cursor traces:', error);
      
      throw error; // Re-throw to let the caller handle it
    } finally {
      // Runs even when the upload above failed, so pending turns from earlier
      // cycles still get their usage.
      try {
        const stateDbPath = resolveStateDbPath(vsInstallationPath);
        if (stateDbPath) {
          await this.usageEnricher.tick(stateDbPath);
        }
      } catch (usageError) {
        captureException(usageError);
        console.error('Error enriching cursor usage:', usageError);
      } finally {
        // Held through enrichment, not released before it. Two overlapping
        // interval callbacks would otherwise run tick() at the same time, and a
        // late write of the pending list would drop turns that track() queued
        // in between, leaving those spans without usage forever.
        this.isProcessing = false;
      }
    }

    return numberOfTracesLogged;
  }
} 
