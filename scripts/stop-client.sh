#!/usr/bin/env bash
# Stops a test client started by launch-client.sh.
set -euo pipefail

instance=${1:-default}
project_dir=$(cd "$(dirname "$0")/.." && pwd)
ready_file="$project_dir/run/$instance/mcptest/ready.json"

if [[ ! -f "$ready_file" ]]; then
    echo "No running client found for instance '$instance' ($ready_file is missing)." >&2
    exit 1
fi
pid=$(sed -n 's/.*"pid":\([0-9]*\).*/\1/p' "$ready_file")
kill "$pid"
echo "Stopped client '$instance' (pid $pid)."
