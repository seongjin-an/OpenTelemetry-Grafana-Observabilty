# OpenTelemetry / Grafana Observability Stack

로컬 개발 환경에서 Spring Boot 서비스의 **트레이스 + 로그 통합 추적**을 위한 observability 스택 정리.

---

## 전체 아키텍처

```
[gateway :18080]  ──┐
                    ├──► OTel Collector (:24317) ──► Tempo   (:13200)  ← 트레이스
[demo    :18081]  ──┘         │
                              └──────────────────► Loki    (:13100)  ← 로그

                                              Grafana (:13000)  ← 시각화
                                           (Loki + Tempo 연동)
```

- **gateway** → Spring Cloud Gateway, 외부 요청 수신 후 demo 로 라우팅
- **demo** → 실제 비즈니스 로직 처리
- **OTel Java Agent** → 자동 계측 (코드 수정 없이 트레이스/로그 수집)
- **OTel Collector** → 수집 중계, `deployment.environment` 속성 추가 후 Tempo/Loki 전달

---

## 인프라 스택

| 컴포넌트 | 이미지 | 외부 포트 | 역할 |
|---|---|---|---|
| OTel Collector | `otel/opentelemetry-collector-contrib:0.103.0` | 24317 (gRPC), 24318 (HTTP) | 텔레메트리 수집 중계 |
| Loki | `grafana/loki:3.0.0` | 13100 | 로그 저장 |
| Tempo | `grafana/tempo:2.5.0` | 13200, 14317 | 트레이스 저장 |
| Grafana | `grafana/grafana:11.0.0` | 13000 | 시각화 |

인프라 기동:
```bash
cd infra
docker compose up -d
```

---

## 서비스 기동/정지/상태 확인

프로젝트 루트에 스크립트 3개가 있다.

```bash
./start.sh    # gateway, demo 백그라운드 기동 (gradlew bootRun)
./stop.sh     # demo → gateway 순서로 graceful stop
./status.sh   # PID 기반 UP/DOWN 및 가동 시간 확인
```

- PID 파일: `logs/gateway.pid`, `logs/demo.pid`
- 로그 파일: `logs/gateway.log`, `logs/demo.log`

---

## OTel Java Agent 설정

두 서비스 모두 `build.gradle` `bootRun` 블록에서 agent 를 주입한다.

**공통 JVM 인수:**

| 인수 | 값 | 설명 |
|---|---|---|
| `-javaagent` | `infra/agent/opentelemetry-javaagent.jar` | 자동 계측 agent |
| `otel.service.name` | `gateway` / `demo` | Grafana 서비스 구분 라벨 |
| `otel.resource.attributes` | `service.namespace=otel-obs` | 네임스페이스 그룹핑 |
| `otel.exporter.otlp.endpoint` | `http://localhost:24317` | OTel Collector |
| `otel.exporter.otlp.protocol` | `grpc` | 전송 프로토콜 |
| `otel.logs.exporter` | `otlp` | 로그 OTLP 전송 활성화 |
| `otel.metrics.exporter` | `none` | 메트릭 비활성화 |
| `otel.propagators` | `tracecontext,baggage` | W3C Trace Context 전파 |

agent 다운로드:
```bash
cd infra
./download-otel-agent.sh        # 기본 v2.6.0
./download-otel-agent.sh 2.7.0  # 버전 지정
```

---

## OTel Collector 파이프라인

```
receivers: otlp (gRPC/HTTP)
    │
processors:
    ├── batch (2s timeout, 1024 size)
    └── resource → deployment.environment=local 추가
    │
exporters:
    ├── traces → otlp/tempo (tempo:4317)
    └── logs   → otlphttp/loki (loki:3100/otlp)
```

---

## Loki 라벨 정책

> Loki 라벨 = **스트림 인덱스 키**
> 라벨 조합 하나 = 하나의 스트림(청크 파일) → **카디널리티가 낮은 속성만** 라벨로 써야 한다.

### 라벨로 승격된 속성 (index_label)

```yaml
# infra/loki/loki-config.yml
otlp_config:
  resource_attributes:
    attributes_config:
      - regex: service.namespace   → service_namespace
      - regex: service.name        → service_name
      - regex: deployment.environment → deployment_environment
```

Loki 3.0 에서 `attribute:` 필드명은 없고 **`regex:`** 를 써야 한다 (내부 타입 `push.plain`).

### trace_id 는 라벨 금지

`trace_id` 는 요청마다 고유한 값 → 라벨로 만들면 수백만 스트림 → OOM/성능 저하.
`allow_structured_metadata: true` 덕분에 **structured metadata** 로 자동 저장되므로 LaQL 필터로 조회한다.

---

## 트레이스-로그 통합 추적

### Grafana Explore — LogQL 로 크로스 서비스 추적

```logql
# namespace 로 묶어 특정 trace 의 gateway + demo 로그 한번에 조회
{service_namespace="otel-obs"} | trace_id = "1a2b3c4d5e6f..."

# 특정 서비스만
{service_namespace="otel-obs", service_name="gateway"} | trace_id = "1a2b3c4d..."

# namespace 전체 에러 로그
{service_namespace="otel-obs"} | severity = "ERROR"
```

### Grafana UI 클릭 플로우

```
Loki 로그 라인
  └── [View Trace] 버튼  → Tempo 트레이스 상세
        └── [Logs] 탭    → 해당 trace_id 의 Loki 로그 (service_namespace 필터)
```

**loki.yml** — structured metadata 에서 `trace_id` 추출 → Tempo 링크:
```yaml
derivedFields:
  - name: TraceID
    matcherType: label
    matcherRegex: trace_id
    datasourceUid: tempo
    urlDisplayLabel: View Trace
```

**tempo.yaml** — 트레이스 상세에서 Loki 로그 조회 시 사용할 태그:
```yaml
tracesToLogsV2:
  datasourceUid: loki
  filterByTraceID: true
  tags:
    - key: service.namespace   # → service_namespace 라벨로 매핑
      value: service_namespace
    - key: service.name
      value: service_name
```

---

## 파일 구조

```
otel-grafana-obs/
├── start.sh                          # 서비스 기동
├── stop.sh                           # 서비스 정지
├── status.sh                         # 상태 확인
├── logs/                             # PID & 로그 (gitignore)
│
├── gateway/
│   ├── build.gradle                  # OTel agent JVM 인수
│   └── src/main/resources/
│       ├── application.yml           # port: 18080, gateway 라우팅
│       └── logback-spring.xml        # trace_id MDC 패턴
│
├── demo/
│   ├── build.gradle                  # OTel agent JVM 인수
│   └── src/main/resources/
│       ├── application.yml           # port: 18081
│       └── logback-spring.xml        # trace_id MDC 패턴
│
└── infra/
    ├── compose.yaml                  # Loki / Tempo / Grafana / OTel Collector
    ├── download-otel-agent.sh        # agent jar 다운로드
    ├── agent/                        # opentelemetry-javaagent.jar
    ├── otel-collector/
    │   └── otel-collector-config.yml # 파이프라인 설정
    ├── loki/
    │   └── loki-config.yml           # 라벨 정책, structured metadata
    ├── tempo/
    │   └── tempo-config.yml          # 트레이스 저장
    └── grafana/provisioning/
        ├── datasources/loki.yml      # derivedFields (View Trace 버튼)
        └── datasources/tempo.yaml    # tracesToLogsV2 (Loki 연동)
```
