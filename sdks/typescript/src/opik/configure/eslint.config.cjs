const globals = require('globals');
const pluginJs = require('@eslint/js');
const tseslint = require('typescript-eslint');

/** @type {import('eslint').Linter.Config[]} */
module.exports = [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'templates/**',
      'eslint.config.cjs',
    ],
  },
  { files: ['src/**/*.{js,mjs,cjs,ts,d.ts}', 'bin.ts'] },
  {
    languageOptions: {
      globals: globals.node,
      parserOptions: {
        tsconfigRootDir: __dirname,
        project: './tsconfig.json',
      },
    },
  },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommended,
];
