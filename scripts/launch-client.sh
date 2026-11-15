#!/usr/bin/env bash
# Starts a test client in the background and waits until its MCP endpoint is ready.
# Prints one line of JSON ({"url": ..., "pid": ..., "log": ...}) on success.
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: scripts/launch-client.sh [options]

  --instance NAME    Name of this client; its game directory is run/NAME (default: default)
  --port PORT        MCP port on 127.0.0.1 (default: 25580). Give each instance its own port.
  --username NAME    Offline player name. The same name always gets the same UUID on an offline-mode server.
  --real-account     Log in with a real Microsoft account through DevAuth (first run asks for a browser login).
  --minimized        Minimize the window once the client has started.
  --timeout SECONDS  How long to wait for the client to become ready (default: 300)
USAGE
}

instance=default
port=25580
username=
real_account=false
minimized=false
timeout=300

while [[ $# -gt 0 ]]; do
    case "$1" in
        --instance) instance=$2; shift 2 ;;
        --port) port=$2; shift 2 ;;
        --username) username=$2; shift 2 ;;
        --real-account) real_account=true; shift ;;
        --minimized) minimized=true; shift ;;
        --timeout) timeout=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

project_dir=$(cd "$(dirname "$0")/.." && pwd)
run_dir="$project_dir/run/$instance"
log="$run_dir/mcptest/launch.log"
health="http://127.0.0.1:$port/health"
mkdir -p "$run_dir/mcptest"

if curl -fs --max-time 2 "$health" >/dev/null 2>&1; then
    echo "Something is already listening on port $port; stop it or choose another --port." >&2
    exit 1
fi

args=("-Pmcptest.instance=$instance" "-Pmcptest.port=$port" "-Pmcptest.realAccount=$real_account" "-Pmcptest.minimized=$minimized")
if [[ -n "$username" ]]; then
    args+=("-Pmcptest.username=$username")
fi

cd "$project_dir"
nohup ./gradlew runClient --console=plain "${args[@]}" >"$log" 2>&1 &
launcher_pid=$!

deadline=$((SECONDS + timeout))
while (( SECONDS < deadline )); do
    if curl -fs --max-time 2 "$health" 2>/dev/null | grep -q '"ready":true'; then
        pid=$(sed -n 's/.*"pid":\([0-9]*\).*/\1/p' "$run_dir/mcptest/ready.json" 2>/dev/null || true)
        printf '{"url":"http://127.0.0.1:%s/mcp","pid":%s,"instance":"%s","log":"%s"}\n' "$port" "${pid:-null}" "$instance" "$log"
        exit 0
    fi
    if ! kill -0 "$launcher_pid" 2>/dev/null; then
        echo "The client exited before becoming ready. Last lines of $log:" >&2
        tail -n 30 "$log" >&2
        exit 1
    fi
    sleep 1
done

echo "The client was not ready after $timeout seconds; see $log" >&2
exit 1
