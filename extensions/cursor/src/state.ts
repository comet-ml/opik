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
