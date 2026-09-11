import * as vscode from 'vscode';
import { PendingUsage, RequestLedger, SessionInfo } from './interface';
import { compactRequestLedger } from './cursor/ledgerRetention';
import { resolveAutomaticTraceCutoff } from './cursor/stateMigration';

export function getSessionInfo(context: vscode.ExtensionContext): Record<string, SessionInfo> {
  return context.globalState.get<Record<string, SessionInfo>>('sessionInfo', {});
}

export function resetGlobalState(context: vscode.ExtensionContext): Thenable<void> {
  return context.globalState.update('sessionInfo', undefined);
}

export function updateSessionInfo(context: vscode.ExtensionContext, sessionInfo: Record<string, SessionInfo>): Thenable<void> {
  return context.globalState.update('sessionInfo', sessionInfo);
}

export function getLastSyncTime(context: vscode.ExtensionContext): number | null {
  return context.globalState.get<number | null>('lastSyncTime', null);
}

export function updateLastSyncTime(context: vscode.ExtensionContext, time: number): Thenable<void> {
  return context.globalState.update('lastSyncTime', time);
}

export function getLastSyncedAt(context: vscode.ExtensionContext): number {
  const storedValue = context.globalState.get<number>('lastSyncedAt');
  
  if (storedValue === undefined) {
    // First time: Default to 30 minutes ago to catch recent conversations
    // including ones that completed just before extension activation
    const thirtyMinutesAgo = Date.now() - (30 * 60 * 1000);
    console.log(`🔄 First sync - will fetch conversations from last 30 minutes`);
    return thirtyMinutesAgo;
  }
  
  return storedValue;
}

export function updateLastSyncedAt(context: vscode.ExtensionContext, timestamp: number): Thenable<void> {
  return context.globalState.update('lastSyncedAt', timestamp);
}

/**
 * The first turn normal polling is allowed to upload.
 *
 * New installations start at activation time, so existing Cursor history is
 * imported only through the explicit command. Upgrades start at the last
 * successful poll, preserving turns created while the previous build was
 * inactive without replaying conversations it already handled.
 */
export async function getOrCreateAutomaticTraceCutoffAt(
  context: vscode.ExtensionContext
): Promise<number> {
  const stored = context.globalState.get<number>('automaticTraceCutoffAt');
  if (stored !== undefined) {
    return stored;
  }

  const previousSync = context.globalState.get<number>('lastSyncedAt');
  const legacySync = context.globalState.get<number | null>('lastSyncTime', null);
  const cutoff = resolveAutomaticTraceCutoff(previousSync, legacySync, Date.now());
  await context.globalState.update('automaticTraceCutoffAt', cutoff);
  return cutoff;
}

export function getPendingUsage(context: vscode.ExtensionContext): PendingUsage[] {
  return context.globalState.get<PendingUsage[]>('pendingUsage', []);
}

export function updatePendingUsage(context: vscode.ExtensionContext, pending: PendingUsage[]): Thenable<void> {
  return context.globalState.update('pendingUsage', pending);
}

export function getRequestLedger(context: vscode.ExtensionContext): RequestLedger {
  return context.globalState.get<RequestLedger>('requestLedger', {});
}

export function updateRequestLedger(context: vscode.ExtensionContext, ledger: RequestLedger): Thenable<void> {
  return context.globalState.update('requestLedger', compactRequestLedger(ledger));
}

export async function resetExtensionState(context: vscode.ExtensionContext): Promise<void> {
  await Promise.all([
    context.globalState.update('sessionInfo', undefined),
    context.globalState.update('lastSyncTime', null),
    context.globalState.update('lastSyncedAt', undefined),
  ]);
}
