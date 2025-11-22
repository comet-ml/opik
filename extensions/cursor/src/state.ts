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

export function resetExtensionState(context: vscode.ExtensionContext) {
  context.globalState.update('sessionInfo', undefined);
  context.globalState.update('lastSyncTime', null);
}
