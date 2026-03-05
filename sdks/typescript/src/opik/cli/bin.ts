#!/usr/bin/env node

import { runCli } from "@/cli";

(async () => {
  const code = await runCli(process.argv.slice(2));
  process.exit(code);
})();
