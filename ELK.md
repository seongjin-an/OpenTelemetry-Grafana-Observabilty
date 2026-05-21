# Elasticsearch / Kibana 통합 가이드

기존 OTel + Loki + Tempo + Grafana 스택에 Elasticsearch / Kibana 를 추가한 작업 기록.
운영 모니터링은 Loki + Grafana, 필드 기반 검색/감사 로그는 Elasticsearch + Kibana 로 역할을 분리했다.

---

## 최종 데이터 흐름

```
App (JSON 로그)
    │
OTel Agent
    │
OTel Collector
    ├── logs/loki  → Loki    → Grafana  (운영 모니터링, 트레이스 연동)
    └── logs/es   → ES      → Kibana   (필드 검색, 감사 로그)

Trace
    └── Tempo → Grafana
```

---

## 변경된 파일 목록

| 파일 | 변경 내용 |
|---|---|
| `infra/compose.yaml` | Elasticsearch, Kibana 컨테이너 추가 |
| `infra/otel-collector/otel-collector-config.yml` | ES exporter, ES 전용 파이프라인 분리 |
| `gateway/build.gradle` | `logstash-logback-encoder:8.1` 활성화 |
| `demo/build.gradle` | `logstash-logback-encoder:8.1` 추가 |
| `gateway/logback-spring.xml` | JSON 인코더, 프레임워크 로거 억제 |
| `demo/logback-spring.xml` | 동일 |
| `gateway/TraceFilter.java` | 감사 로그 (user, ip, method, path, status, duration) |

---

## 1. 인프라 — compose.yaml

```yaml
elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:8.14.0
  ports:
    - "19200:9200"
  environment:
    - discovery.type=single-node
    - xpack.security.enabled=false          # 로컬 개발용
    - xpack.ml.enabled=false                # ML 비활성화 — 메모리 절약
    - ES_JAVA_OPTS=-Xms512m -Xmx512m        # 힙 512MB (기본 4GB)
    - cluster.routing.allocation.disk.threshold_enabled=false  # 로컬 디스크 워터마크 비활성화

kibana:
  image: docker.elastic.co/kibana/kibana:8.14.0
  ports:
    - "15601:5601"
  environment:
    - ELASTICSEARCH_HOSTS=http://elasticsearch:9200
    - NODE_OPTIONS=--max-old-space-size=512  # Node.js 힙 512MB
```

**로컬 메모리 절약 포인트**
- ES 힙: `512m` 고정 (`-Xms512m -Xmx512m`) — 기본 4GB 대비 87% 절약
- ML 기능 비활성화 (`xpack.ml.enabled=false`)
- Kibana Node.js 힙: `512m`
- 합산 약 1GB 추가 사용

**디스크 워터마크 이슈**
ES 8.x 는 디스크 사용률 95% 초과 시 flood stage watermark 를 발동해 모든 인덱스를 read-only 로 잠근다.
이 상태가 되면 Kibana 가 내부 인덱스를 쓰지 못해 `server is not ready yet` 오류가 발생한다.
로컬 개발 환경에서는 워터마크 체크를 비활성화해 해결했다. 운영 환경에서는 절대 끄면 안 된다.

---

## 2. OTel Collector — 파이프라인 분리

로그 파이프라인을 Loki 용과 ES 용으로 분리했다.
ES 전송 전에 불필요한 resource 속성을 `transform` 프로세서로 제거해 문서 크기를 줄인다.

```yaml
processors:
  transform/clean_for_es:
    log_statements:
      - context: resource
        statements:
          - delete_key(attributes, "process.command_args")   # 전체 클래스패스 (수 KB)
          - delete_key(attributes, "process.executable.path")
          - delete_key(attributes, "process.runtime.description")
          - delete_key(attributes, "process.runtime.name")
          - delete_key(attributes, "process.runtime.version")
          - delete_key(attributes, "process.pid")
          - delete_key(attributes, "os.description")
          - delete_key(attributes, "os.type")
          - delete_key(attributes, "host.arch")
          - delete_key(attributes, "telemetry.distro.name")
          - delete_key(attributes, "telemetry.distro.version")
          - delete_key(attributes, "telemetry.sdk.language")
          - delete_key(attributes, "telemetry.sdk.name")
          - delete_key(attributes, "telemetry.sdk.version")

service:
  pipelines:
    logs/loki:                                # Loki — 원본 그대로
      receivers: [otlp]
      processors: [batch, resource]
      exporters: [otlphttp/loki]
    logs/es:                                  # ES — 노이즈 제거 후 전송
      receivers: [otlp]
      processors: [batch, resource, transform/clean_for_es]
      exporters: [elasticsearch]
```

---

## 3. ES Index Template + Ingest Pipeline

### 문제: OTLP 원형 필드명

OTel Collector 가 ES 로 전송하는 원본 문서는 OTLP 스펙 그대로라서 필드명이 불편하다.

```json
{
  "Body": "received request",       ← message 가 아님
  "TraceId": "177cd5f7...",         ← trace_id 가 아님
  "SeverityText": "INFO",           ← level 이 아님
  "Resource": { ... }               ← 서비스 정보가 중첩 객체 안에
}
```

### 해결: Ingest Pipeline 으로 평탄화

문서가 ES 에 색인되기 전에 Ingest Pipeline 이 필드를 변환한다.
OTel Collector 의 `elasticsearch` exporter 에 `pipeline: otel-logs-pipeline` 을 지정하면 된다.

```bash
# Index Template 생성 (인덱스 생성 전에 반드시 먼저 실행)
curl -X PUT "localhost:19200/_index_template/otel-logs-template" \
  -H "Content-Type: application/json" \
  -d '{
    "index_patterns": ["otel-logs*"],
    "template": {
      "mappings": {
        "properties": {
          "@timestamp":        {"type": "date"},
          "message":           {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
          "level":             {"type": "keyword"},
          "trace_id":          {"type": "keyword"},
          "span_id":           {"type": "keyword"},
          "service_name":      {"type": "keyword"},
          "service_namespace": {"type": "keyword"},
          "environment":       {"type": "keyword"},
          "host_name":         {"type": "keyword"},
          "logger":            {"type": "keyword"},
          "scope":             {"type": "keyword"}
        }
      }
    }
  }'

# Ingest Pipeline 생성
curl -X PUT "localhost:19200/_ingest/pipeline/otel-logs-pipeline" \
  -H "Content-Type: application/json" \
  -d '{
    "description": "OTLP 필드 평탄화",
    "processors": [
      {"rename": {"field": "Body",         "target_field": "message",           "ignore_missing": true}},
      {"rename": {"field": "TraceId",      "target_field": "trace_id",          "ignore_missing": true}},
      {"rename": {"field": "SpanId",       "target_field": "span_id",           "ignore_missing": true}},
      {"rename": {"field": "SeverityText", "target_field": "level",             "ignore_missing": true}},
      {"set": {"field": "service_name",      "value": "{{Resource.service.name}}",           "ignore_empty_value": true}},
      {"set": {"field": "service_namespace", "value": "{{Resource.service.namespace}}",      "ignore_empty_value": true}},
      {"set": {"field": "environment",       "value": "{{Resource.deployment.environment}}", "ignore_empty_value": true}},
      {"set": {"field": "host_name",         "value": "{{Resource.host.name}}",              "ignore_empty_value": true}},
      {"set": {"field": "scope",             "value": "{{Scope.name}}",                      "ignore_empty_value": true}},
      {"remove": {"field": ["Resource", "Scope", "SeverityNumber", "TraceFlags"], "ignore_missing": true}}
    ]
  }'
```

### 변환 결과

```json
{
  "@timestamp":        "2026-05-20T08:25:35.799Z",
  "message":           "audit",
  "level":             "INFO",
  "trace_id":          "177cd5f7773b9bd77eeb950d65f5617b",
  "span_id":           "ecb44e32a990ec3a",
  "service_name":      "gateway",
  "service_namespace": "otel-obs",
  "environment":       "local",
  "host_name":         "anseongjin-ui-MacBookPro.local",
  "scope":             "com.ansj.gateway.config.TraceFilter",
  "audit":             true,
  "method":            "GET",
  "path":              "/demo/api/demo",
  "status":            200,
  "ip":                "127.0.0.1",
  "user":              "anonymous",
  "duration_ms":       42,
  "result":            "ON_COMPLETE"
}
```

> 인덱스가 이미 잘못된 매핑으로 생성됐다면 반드시 삭제 후 재생성해야 한다.
> `curl -X DELETE "localhost:19200/otel-logs"`

---

## 4. 로그 포맷 — JSON (LogstashEncoder)

ES 에서 필드별 검색이 되려면 로그가 JSON 형식이어야 한다.
`logstash-logback-encoder` 의 `LogstashEncoder` 를 쓰면 MDC 필드(trace_id 등)가 자동으로 포함된다.

```xml
<!-- logback-spring.xml -->
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>

  <!-- 프레임워크 시작 로그 억제 — INFO 노이즈 차단 -->
  <logger name="org.springframework" level="WARN"/>
  <logger name="io.netty"            level="WARN"/>
  <logger name="reactor"             level="WARN"/>
  <logger name="com.netflix"         level="WARN"/>

  <logger name="com.ansj"   level="INFO"/>  <!-- gateway -->
  <logger name="com.example" level="INFO"/> <!-- demo -->

  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
  </root>
</configuration>
```

**주의: `<providers>` 블록은 사용하지 않는다.**
`LogstashEncoder` 안에 `<providers>` 를 넣으면 Spring Boot 초기화 시
`java.lang.IllegalStateException at LoggingApplicationListener.java:347` 오류가 발생한다.
`<providers>` 는 `LoggingEventCompositeJsonEncoder` 전용 문법이다.

**프레임워크 로거 억제 이유**
`org.springframework`, `io.netty`, `reactor` 는 앱 시작 시 수십~수백 줄의 INFO 로그를 뿜는다.
이를 WARN 으로 올려두면 시작 노이즈가 제거되고, 실제 경고/오류는 여전히 잡힌다.

---

## 5. 감사 로그 — TraceFilter

OTel agent 는 HTTP span 을 자동 계측하지만 "누가 요청했는지" 같은 비즈니스 맥락은 코드에서 직접 로깅해야 한다.
`StructuredArguments.kv()` 를 쓰면 JSON 필드로 정확히 분리돼 ES 에서 바로 검색 가능하다.

```java
// gateway/TraceFilter.java 핵심 로직
log.info("audit",
    kv("audit",       true),      // 감사 로그 필터링용 플래그
    kv("method",      method),    // GET, POST ...
    kv("path",        path),      // /demo/api/demo
    kv("status",      status),    // 200, 404, 500 ...
    kv("ip",          ip),        // X-Forwarded-For 또는 remoteAddress
    kv("user",        user),      // X-User-Id 헤더 (없으면 anonymous)
    kv("duration_ms", duration),  // 응답 시간
    kv("result",      signal.name()) // ON_COMPLETE / ON_ERROR / CANCEL
);
```

**user 필드 확장**
현재는 `X-User-Id` 헤더를 그대로 읽는 단순 구현이다.
실제 운영에서는 `resolveUser()` 내부를 JWT 파싱 또는 인증 서버 연동으로 교체하면 된다.

**demo 서비스 감사 로그**
데이터 조회/변경 같은 비즈니스 이벤트는 서비스/컨트롤러에서 동일하게 `kv()` 로 추가하면 된다.

```java
log.info("audit",
    kv("audit",    true),
    kv("action",   "GET_DEMO"),
    kv("resource", name),
    kv("user",     user),
    kv("result",   "SUCCESS")
);
```

---

## 6. Kibana Data View 생성 (최초 1회)

앱 기동 후 로그가 ES 에 쌓이면 Kibana 에서 Data View 를 만들어야 Discover 에서 조회된다.

```
http://localhost:15601
  → Management → Stack Management → Data Views → Create data view
  Name:          otel-logs
  Index pattern: otel-logs*
  Timestamp:     @timestamp
  → Save
```

또는 API 로 생성:
```bash
curl -X POST "localhost:15601/api/data_views/data_view" \
  -H "kbn-xsrf: true" \
  -H "Content-Type: application/json" \
  -d '{"data_view": {"title": "otel-logs*", "name": "otel-logs", "timeFieldName": "@timestamp"}}'
```

---

## 7. 주요 쿼리

### Kibana Discover (KQL)

```kql
# 감사 로그만 필터
audit: true

# 특정 사용자 행동 추적
audit: true and user: "user123"

# 에러 응답만
audit: true and status >= 400

# 특정 경로 접근 이력
audit: true and path: "/api/admin*"

# trace_id 로 크로스 서비스 로그 조회
trace_id: "177cd5f7773b9bd77eeb950d65f5617b"

# 에러 레벨 전체
level: "ERROR"
```

### ES API (DSL)

```bash
# 감사 로그 — 특정 유저 + 에러 응답
curl -X GET "localhost:19200/otel-logs/_search?pretty" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "bool": {
        "must": [
          {"term": {"audit": true}},
          {"term": {"user": "user123"}},
          {"range": {"status": {"gte": 400}}}
        ]
      }
    },
    "sort": [{"@timestamp": "desc"}]
  }'

# trace_id 로 크로스 서비스 추적
curl -X GET "localhost:19200/otel-logs/_search?pretty" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {"term": {"trace_id": "177cd5f7773b9bd77eeb950d65f5617b"}},
    "sort": [{"@timestamp": "asc"}]
  }'

# 인덱스 매핑 확인
curl "localhost:19200/otel-logs/_mapping?pretty"

# 인덱스 삭제 (매핑 잘못됐을 때)
curl -X DELETE "localhost:19200/otel-logs"
```

---

## 접속 정보

| 서비스 | URL | 비고 |
|---|---|---|
| Kibana | http://localhost:15601 | Data View: `otel-logs*` |
| Elasticsearch | http://localhost:19200 | REST API |
| Grafana | http://localhost:13000 | Loki + Tempo |
