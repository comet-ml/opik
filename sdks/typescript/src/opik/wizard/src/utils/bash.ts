import * as child_process from 'child_process';

export function executeSync(command: string): string {
  const output = child_process.execSync(command);
  return output.toString();
}

export function execute(command: string): Promise<string> {
  return new Promise((resolve, reject) => {
    child_process.exec(command, (error, stdout, _) => {
      if (error) {
        reject(error);
        return;
      }

      resolve(stdout);
    });
  });
}
