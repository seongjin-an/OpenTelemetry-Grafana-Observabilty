#!/usr/bin/env bash
# OpenTelemetry Java Agent 다운로드 스크립트
# ./gradlew bootRun 시 JVM 에 -javaagent 로 붙여서 사용한다.
set -euo pipefail

AGENT_VERSION="${AGENT_VERSION:-2.6.0}"
TARGET_DIR="$(cd "$(dirname "$0")" && pwd)/agent"
AGENT_PATH="${TARGET_DIR}/opentelemetry-javaagent.jar"

mkdir -p "${TARGET_DIR}"

if [[ -f "${AGENT_PATH}" ]]; then
  echo "[otel-agent] 이미 존재함: ${AGENT_PATH}"
  echo "             버전 강제 재설치는 AGENT_VERSION=x.y.z 로 실행하거나 파일 삭제 후 재실행"
  exit 0
fi

URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${AGENT_VERSION}/opentelemetry-javaagent.jar"
echo "[otel-agent] 다운로드: ${URL}"

if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 -o "${AGENT_PATH}" "${URL}"
elif command -v wget >/dev/null 2>&1; then
  wget -O "${AGENT_PATH}" "${URL}"
else
  echo "curl 또는 wget 이 필요합니다." >&2
  exit 1
fi

echo "[otel-agent] 저장 완료: ${AGENT_PATH}"
echo "             각 서비스 build.gradle 의 bootRun 에서 이 경로를 참조합니다."