import * as vscode from 'vscode';
import { SessionInfo } from './interface';

export function getSessionInfo(context: vscode.ExtensionContext): Record<string, SessionInfo> {
  return context.globalState.get<Record<string, SessionInfo>>('sessionInfo', {});
}

export function resetGlobalState(context: vscode.ExtensionContext) {
  context.globalState.update('sessionInfo', undefined);
}

export function updateSessionInfo(context: vscode.ExtensionContext, sessionInfo: Record<string, SessionInfo>) {
  context.globalState.update('sessionInfo', sessionInfo);
}

export function getLastSyncTime(context: vscode.ExtensionContext): number | null {
  return context.globalState.get<number | null>('lastSyncTime', null);
}

export function updateLastSyncTime(context: vscode.ExtensionContext, time: number) {
  context.globalState.update('lastSyncTime', time);
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

export function updateLastSyncedAt(context: vscode.ExtensionContext, timestamp: number) {
  context.globalState.update('lastSyncedAt', timestamp);
}

export function resetExtensionState(context: vscode.ExtensionContext) {
  context.globalState.update('sessionInfo', undefined);
  context.globalState.update('lastSyncTime', null);
  context.globalState.update('lastSyncedAt', undefined);
}
