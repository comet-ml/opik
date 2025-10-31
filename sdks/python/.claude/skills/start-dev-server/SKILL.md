---
name: start-dev-server
description: Starts the local development servers for backend and frontend.
allowed-tools: Read, Grep, Glob, Execute
---

### Overview

This skill starts the local development servers for backend and frontend. Also, it starts the necessary
services for the backend in the Docker container. The skill is useful for developers who want to work on the
backend and frontend of the project.


### Example usage

"Start the local development servers for backend and frontend"
"Start the local development servers"
"Stop the local development servers"
"Show logs of the local development servers"

### Scripts

#### Start the local development servers for backend and frontend
- GIT_ROOT/scripts/dev-runner.sh --start

#### Stop the local development servers for backend and frontend
- GIT_ROOT/scripts/dev-runner.sh --stop

#### Shows logs of the local development servers for backend and frontend
- GIT_ROOT/scripts/dev-runner.sh --logs
