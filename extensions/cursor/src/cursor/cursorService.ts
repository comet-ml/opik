import * as vscode from 'vscode';
import { findAndReturnNewTraces, resolveStateDbPath } from './sessionManager';
import { applyTurnUsage, logTracesToOpik } from '../opik';
import {
  getLastSyncedAt,
  getRequestLedger,
  getSessionInfo,
  updateLastSyncedAt,
  updateRequestLedger,
  updateSessionInfo,
} from '../state';
import { captureException } from '../sentry';
import { UsageEnricher } from './usage';
import { acknowledgeUploadedTraces } from './requestIdentity';
import { aggregateTurnUsage } from './usageAggregation';

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
      const ledger = getRequestLedger(this.context);
      const entry = ledger[pending.requestKey];
      if (!entry) {
        await applyTurnUsage(this.apiKey, pending, usage);
        return;
      }

      const delivery = entry.ownerComposerId === pending.composerId
        ? entry
        : entry.forkCopies[pending.composerId];
      if (!delivery) {
        await applyTurnUsage(this.apiKey, pending, usage);
        return;
      }

      delivery.usageByRevision ??= {};
      delivery.usageByRevision[pending.usageKey] = usage;
      const aggregate = aggregateTurnUsage(Object.values(delivery.usageByRevision));
      await applyTurnUsage(this.apiKey, pending, aggregate);
      delivery.usageStatus = 'complete';
      entry.lastSeenAt = Date.now();
      await updateRequestLedger(this.context, ledger);
    });
  }

  /**
   * Process cursor traces and log them to Opik
   */
  async processCursorTraces(
    apiKey: string,
    vsInstallationPath: string,
    options: { includeHistorical?: boolean; automaticCutoffAt: number }
  ): Promise<number> {
    // Prevent concurrent processing to avoid duplicates
    if (this.isProcessing) {
      console.log('⏳ Cursor trace processing already in progress, skipping this cycle');
      return 0;
    }

    this.isProcessing = true;
    this.apiKey = apiKey;
    let numberOfTracesLogged = 0;
    let sessionInfo = getSessionInfo(this.context);
    const requestLedger = getRequestLedger(this.context);
    const lastSyncedAt = getLastSyncedAt(this.context);
    // Capture current time before processing to avoid race conditions
    const currentSyncTime = Date.now();
    
    try {
      const cursorResult = await findAndReturnNewTraces(
        this.context,
        vsInstallationPath,
        requestLedger,
        lastSyncedAt,
        currentSyncTime,
        options.includeHistorical ?? false,
        options.automaticCutoffAt
      );
      
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

          // Persist pending identities before network I/O. A failed upload is
          // retried with exactly the same UUIDv7 ids on the next cycle.
          if (!this.usageEnricher.isEnabled()) {
            for (const trace of tracesData) {
              if (trace.cost_owner && requestLedger[trace.request_key]) {
                const entry = requestLedger[trace.request_key];
                if (entry.ownerComposerId === trace.thread_id) {
                  entry.usageStatus = 'disabled';
                } else if (trace.thread_id && entry.forkCopies[trace.thread_id]) {
                  entry.forkCopies[trace.thread_id].usageStatus = 'disabled';
                }
              }
            }
          }
          await updateRequestLedger(this.context, requestLedger);

          const loggedTurns = await logTracesToOpik(apiKey, tracesData);
          // Trace delivery is durable independently from best-effort usage
          // queue persistence. Never re-upload a trace because the local cost
          // work queue temporarily failed to save.
          acknowledgeUploadedTraces(tracesData, requestLedger);
          await updateRequestLedger(this.context, requestLedger);
          await this.usageEnricher.track(loggedTurns);
          
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
          // Reconciliation can still populate the ledger while migrating an
          // installation whose already-sent traces must not be uploaded again.
          await updateRequestLedger(this.context, requestLedger);
        }

        // Update lastSyncedAt after successful processing (even if no traces uploaded)
        // This prevents re-querying the same conversations
        await Promise.all([
          updateSessionInfo(this.context, sessionInfo),
          updateLastSyncedAt(this.context, currentSyncTime),
        ]);
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
