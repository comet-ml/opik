#!/usr/bin/env node

import { runCli } from "./cli";

process.exit(runCli(process.argv.slice(2)));
