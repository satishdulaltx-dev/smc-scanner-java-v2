#!/bin/bash
# SD Scanner — start script
# Loads API keys from ../smc_scanner/.env (Python project) if present, then starts the app

ENV_FILE="$(dirname "$0")/../smc_scanner/.env"
if [ -f "$ENV_FILE" ]; then
    echo "Loading env from $ENV_FILE"
    export $(grep -v '^#' "$ENV_FILE" | grep '=' | xargs)
fi

export PATH="/opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/bin:$PATH"
cd "$(dirname "$0")"
mvn spring-boot:run
