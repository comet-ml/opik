import * as fs from 'fs';
import chalk from 'chalk';
import path from 'path';
import clack from '../utils/clack';
import { debug } from '../utils/debug';

type CreateOpikClientStepOptions = {
  installDir: string;
  isTypeScript: boolean;
};

export const createOpikClientStep = async ({
  installDir,
  isTypeScript,
}: CreateOpikClientStepOptions): Promise<string> => {
  debug(
    `Creating opik-client file in ${installDir}, TypeScript: ${isTypeScript}`,
  );

  // Check if src directory exists and use it as target
  const srcDir = path.join(installDir, 'src');
  const targetDir = fs.existsSync(srcDir) ? srcDir : installDir;
  debug(`Using target directory: ${targetDir}`);

  const fileExtension = isTypeScript ? 'ts' : 'js';
  const fileName = `opik-client.${fileExtension}`;
  const filePath = path.join(targetDir, fileName);
  debug(`Target file path: ${filePath}`);

  // Check if file already exists
  if (fs.existsSync(filePath)) {
    debug(`File ${fileName} already exists`);
    clack.log.info(`${chalk.cyan(fileName)} already exists, skipping creation`);
    return fileName;
  }

  // Read the template
  // When compiled, __dirname is dist/src/steps, so we need to go up two levels to dist, then into templates
  const templatePath = path.join(
    __dirname,
    '..',
    '..',
    'templates',
    'opik-client.ts',
  );
  debug(`Reading template from: ${templatePath}`);

  let content: string;
  try {
    content = await fs.promises.readFile(templatePath, 'utf8');
    debug(`Template read successfully, length: ${content.length} characters`);
  } catch (error) {
    debug(`Failed to read template: ${error}`);
    throw new Error(`Failed to read template from ${templatePath}: ${error}`);
  }

  // If JavaScript, convert imports to require syntax
  if (!isTypeScript) {
    debug('Converting TypeScript template to JavaScript');
    content = content
      .replace(
        /import { Opik } from 'opik';/g,
        "const { Opik } = require('opik');",
      )
      .replace(/export const client/g, 'const client')
      .replace(/export { Opik }/g, '')
      .replace(/\/\*\*[\s\S]*?\*\//g, (match) => {
        // Keep the file header comment but update TypeScript references
        if (match.includes('Opik client configuration')) {
          return match.replace('TypeScript/Node.js', 'Node.js');
        }
        return match;
      });

    // Add module.exports at the end
    content += '\n\nmodule.exports = { client, Opik };\n';
    debug('Conversion to JavaScript completed');
  }

  // Write the file
  debug(`Writing file to ${filePath}`);
  try {
    await fs.promises.writeFile(filePath, content, 'utf8');
    debug('File written successfully');
  } catch (error) {
    debug(`Failed to write file: ${error}`);
    throw new Error(`Failed to write file to ${filePath}: ${error}`);
  }

  clack.log.success(`Created ${chalk.bold.cyan(fileName)}`);

  return fileName;
};
