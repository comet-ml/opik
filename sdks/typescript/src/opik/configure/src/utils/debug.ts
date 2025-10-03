import chalk from 'chalk';
import { prepareMessage } from './logging';
import clack from './clack';

let debugEnabled = false;

export function debug(...args: unknown[]) {
  if (!debugEnabled) {
    return;
  }

  const msg = args.map((a) => prepareMessage(a)).join(' ');

  clack.log.info(chalk.dim(msg));
}

export function debugError(...args: unknown[]) {
  // Always log errors, regardless of debug mode
  const msg = args.map((a) => prepareMessage(a)).join(' ');
  clack.log.error(chalk.red(msg));
}

export function enableDebugLogs() {
  debugEnabled = true;
}

export function disableDebugLogs() {
  debugEnabled = false;
}
