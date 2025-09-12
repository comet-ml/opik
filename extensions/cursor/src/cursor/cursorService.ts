import * as vscode from 'vscode';
import { findAndReturnNewTraces } from './sessionManager';
import { logTracesToOpik } from '../opik';
import { getSessionInfo, updateSessionInfo } from '../state';
import { captureExceptionWithContext, logger } from '../sentry';

export class CursorService {
  private context: vscode.ExtensionContext;
  private isProcessing: boolean = false;

  constructor(context: vscode.ExtensionContext) {
    this.context = context;
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
    let numberOfTracesLogged = 0;
    let sessionInfo = getSessionInfo(this.context);
    const startTime = Date.now();
    
    try {
      const cursorResult = await findAndReturnNewTraces(this.context, vsInstallationPath, sessionInfo);
      
      if (cursorResult && cursorResult.tracesData) {
        const { tracesData, updatedSessionInfo } = cursorResult;
        
        // Validate trace data quality
        const invalidTraces = tracesData.filter(trace => 
          !trace.input?.input || 
          !trace.output?.output || 
          !trace.thread_id ||
          !trace.project_name
        );
        
        if (tracesData.length > 0) {
          console.log(`📤 Logging ${tracesData.length} cursor traces to Opik`);
          
          // Use a generic session ID for BI logging since we now have multiple composer sessions
          const biSessionId = 'cursor-multi-session';
          
          try {
            await logTracesToOpik(apiKey, tracesData);
          } catch (opikError) {
            throw opikError;
          }
          
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
              captureExceptionWithContext(sessionError as Error, {
                operation: 'update_session',
                sessionId: sessionId
              });
              logger.error(`Error updating session ${sessionId}`, { 
                error: sessionError instanceof Error ? sessionError.message : String(sessionError),
                sessionId: sessionId 
              });
              // Continue with other sessions even if one fails
            }
          });

          numberOfTracesLogged += tracesData.length;
          console.log(`✅ Successfully logged ${numberOfTracesLogged} cursor traces across ${Object.keys(updatedSessionInfo).length} composer sessions`);
        } else {
          console.log(`ℹ️ No new cursor traces to log`);
        }

        updateSessionInfo(this.context, sessionInfo);
        
        // Log processing performance and stats
        const processingTime = Date.now() - startTime;
        
        // Count total bubbles for stats
        const totalBubbles = tracesData.reduce((sum, trace) => {
          return sum + (trace.metadata?.totalBubbles || 0);
        }, 0);
      } else {
        console.log(`⚠️ No cursor data returned`);
      }
    } catch (error) {
      const errorContext = {
        operation: 'process_cursor_traces',
        hasApiKey: !!apiKey,
        installationPath: !!vsInstallationPath
      };

      // Enhanced error logging based on error type
      if (error instanceof Error) {
        if (error.message.includes('SQLITE') || error.message.includes('database')) {
          captureExceptionWithContext(error, { ...errorContext, errorType: 'database' });
          logger.error('Database error processing cursor traces', { 
            error: error.message,
            stack: error.stack 
          });
        } else {
          captureExceptionWithContext(error, { ...errorContext, errorType: 'general' });
          logger.error('General error processing cursor traces', { 
            error: error.message,
            stack: error.stack 
          });
        }
      } else {
        captureExceptionWithContext(new Error(String(error)), { ...errorContext, errorType: 'unknown' });
        logger.error('Unknown error processing cursor traces', { error: String(error) });
      }
      
      throw error; // Re-throw to let the caller handle it
    } finally {
      // Always reset the processing flag, even if an error occurs
      this.isProcessing = false;
    }

    return numberOfTracesLogged;
  }
} 
