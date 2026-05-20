#!/bin/bash

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$BASE_DIR/logs"

check_service() {
  local name=$1
  local pid_file="$LOG_DIR/${name}.pid"

  if [ ! -f "$pid_file" ]; then
    echo "[DOWN] $name: PID file not found"
    return
  fi

  local pid
  pid=$(cat "$pid_file")

  if kill -0 "$pid" 2>/dev/null; then
    local uptime
    uptime=$(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ')
    echo "[UP]   $name (PID $pid, uptime: ${uptime:-unknown})"
  else
    echo "[DOWN] $name: process $pid is not running (stale PID file)"
  fi
}

echo "=== Service Status ==="
check_service gateway
check_service demo
echo "====================="
