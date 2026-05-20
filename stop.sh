#!/bin/bash

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$BASE_DIR/logs"

stop_service() {
  local name=$1
  local pid_file="$LOG_DIR/${name}.pid"

  if [ ! -f "$pid_file" ]; then
    echo "[SKIP] $name: PID file not found"
    return
  fi

  local pid
  pid=$(cat "$pid_file")

  if ! kill -0 "$pid" 2>/dev/null; then
    echo "[SKIP] $name: process $pid is not running"
    rm -f "$pid_file"
    return
  fi

  echo "[STOP] $name (PID $pid) ..."
  kill "$pid"

  local timeout=15
  while kill -0 "$pid" 2>/dev/null && [ $timeout -gt 0 ]; do
    sleep 1
    ((timeout--))
  done

  if kill -0 "$pid" 2>/dev/null; then
    echo "[KILL] $name did not stop gracefully, force killing ..."
    kill -9 "$pid"
  fi

  rm -f "$pid_file"
  echo "[OK]   $name stopped"
}

stop_service demo
stop_service gateway
