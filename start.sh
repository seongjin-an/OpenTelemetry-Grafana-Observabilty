#!/bin/bash

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$BASE_DIR/logs"
mkdir -p "$LOG_DIR"

start_service() {
  local name=$1
  local dir="$BASE_DIR/$name"
  local log="$LOG_DIR/${name}.log"
  local pid_file="$LOG_DIR/${name}.pid"

  if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    echo "[SKIP] $name is already running (PID $(cat "$pid_file"))"
    return
  fi

  echo "[START] $name ..."
  cd "$dir"
  nohup ./gradlew bootRun > "$log" 2>&1 &
  echo $! > "$pid_file"
  echo "[OK]   $name started (PID $!), log: $log"
  cd "$BASE_DIR"
}

start_service gateway
start_service demo
