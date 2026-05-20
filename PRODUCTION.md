# 운영 환경 적용 가이드

현재 스택(OTel + Loki + Tempo + Grafana)을 운영에 투입할 때 고려해야 할 사항 정리.
현재 설정은 개발/테스트 수준이며, 아래 항목들을 단계적으로 보강해야 한다.

---

## 현재 설정과 운영 설정 비교

| 항목 | 현재 (개발) | 운영 목표 |
|---|---|---|
| 스토리지 | 로컬 파일시스템 | MinIO (온프레미스) 또는 S3 |
| 인증 | 없음 | Grafana OAuth / Loki 멀티테넌시 |
| 트레이스 수집 | 100% 전량 수집 | Tail-based Sampling |
| 메트릭 | 비활성화 | Prometheus 연동 |
| OTel Collector | 단일 인스턴스 | HA + 큐 설정 |
| 알림 | 없음 | Grafana Alert Rules |
| 데이터 보존 | 미설정 | Retention 정책 명시 |
| 리소스 제한 | 없음 | CPU/메모리 limits |

---

## 1단계 — 필수 (운영 투입 전 반드시)

### 스토리지: MinIO (폐쇄망/온프레미스)

오브젝트 스토리지 없이 파일시스템으로 운영하면 컨테이너 재시작 시 데이터 유실.
클라우드 S3/GCS 사용 불가한 폐쇄망 환경이면 **MinIO** 가 사실상 표준이다.
MinIO 는 S3 API 를 100% 호환하므로 Loki/Tempo 설정에서 endpoint 만 바꾸면 된다.

```yaml
# compose.yaml 에 추가
minio:
  image: minio/minio:latest
  ports:
    - "19000:9000"   # S3 API
    - "19001:9001"   # 관리 Web UI
  environment:
    MINIO_ROOT_USER: minioadmin
    MINIO_ROOT_PASSWORD: minioadmin   # 운영 시 반드시 강한 패스워드로 변경
  command: server /data --console-address ":9001"
  volumes:
    - /data/minio:/data              # 대용량 디스크 마운트 경로
```

```yaml
# loki-config.yml — filesystem → s3(MinIO)
common:
  storage:
    s3:
      endpoint: minio:9000
      bucketnames: loki
      access_key_id: minioadmin
      secret_access_key: minioadmin
      insecure: true
      s3forcepathstyle: true  # MinIO 필수

schema_config:
  configs:
    - from: 2024-01-01
      object_store: s3        # filesystem 에서 변경
```

```yaml
# tempo-config.yml — local → s3(MinIO)
storage:
  trace:
    backend: s3
    s3:
      endpoint: minio:9000
      bucket: tempo
      access_key: minioadmin
      secret_key: minioadmin
      insecure: true
      forcepathstyle: true
```

MinIO 디스크 용량이 핵심이다. MinIO 서버는 별도 스펙 좋은 서버에 분리하고
나머지(Loki, Tempo, Grafana, Collector)는 같은 서버에 올려도 무방하다.

---

### 데이터 보존 정책 (Retention)

설정 안 하면 디스크가 무한정 쌓인다. 서비스 특성에 맞게 기간을 정해야 한다.

```yaml
# loki-config.yml
limits_config:
  retention_period: 30d    # 로그 30일 보관

compactor:
  working_directory: /loki/compactor
  retention_enabled: true
  retention_delete_delay: 2h
```

```yaml
# tempo-config.yml
compactor:
  compaction:
    block_retention: 72h   # 트레이스 3일 보관 (운영 환경에 따라 조정)
```

> 보안 감사 로그(로그인 이력, 권한 변경 등)는 법적으로 보관 기간이 정해진 경우가 있다.
> 해당 로그는 Loki 가 아닌 Elasticsearch 나 별도 스토리지에 장기 보관을 권장한다.

---

### Tail-based Sampling (트레이스 샘플링)

트래픽이 많은 환경에서 트레이스를 100% 수집하면 Tempo 디스크가 빠르게 찬다.
Tail-based Sampling 은 요청이 **완전히 끝난 후** 결과를 보고 보관 여부를 결정한다.
에러/느린 요청은 무조건 저장하고, 정상 요청은 일부만 저장하는 방식이다.

```yaml
# otel-collector-config.yml 에 추가
processors:
  tail_sampling:
    decision_wait: 10s       # 트레이스 완성 대기 시간
    num_traces: 50000        # 메모리에 보관할 최대 트레이스 수
    policies:
      # 에러 발생한 트레이스는 무조건 저장
      - name: keep-errors
        type: status_code
        status_code: { status_codes: [ERROR] }

      # 2초 이상 걸린 트레이스는 저장
      - name: keep-slow-requests
        type: latency
        latency: { threshold_ms: 2000 }

      # 나머지 정상 트레이스는 10%만 저장
      - name: sample-normal
        type: probabilistic
        probabilistic: { sampling_percentage: 10 }

service:
  pipelines:
    traces:
      processors: [tail_sampling, batch, resource]  # tail_sampling 추가
```

> 로그는 샘플링하지 않는다. 로그는 전량 Loki 에 저장해야 장애 원인 분석이 가능하다.
> 샘플링은 트레이스 전용 개념이다.

---

## 2단계 — 안정화

### 메트릭 파이프라인 활성화

현재 메트릭이 꺼져 있어서 JVM 힙, CPU, HTTP 지표를 볼 수 없다.
운영에서 장애 원인을 빠르게 찾으려면 메트릭이 필수다.

```groovy
// build.gradle — metrics 활성화
"-Dotel.metrics.exporter=otlp",     // none → otlp 로 변경
```

```yaml
# otel-collector-config.yml 에 추가
exporters:
  prometheusremotewrite:
    endpoint: http://prometheus:9090/api/v1/write

service:
  pipelines:
    metrics:                         # 파이프라인 추가
      receivers: [otlp]
      processors: [batch, resource]
      exporters: [prometheusremotewrite]
```

```yaml
# compose.yaml 에 Prometheus 추가
prometheus:
  image: prom/prometheus:latest
  ports:
    - "19090:9090"
  volumes:
    - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
```

Grafana 에서 Prometheus 데이터소스 추가 후 JVM 대시보드(ID: 4701) 임포트하면
JVM 힙, GC, 스레드, HTTP 요청수/응답시간을 바로 볼 수 있다.

---

### OTel Collector 안정화

현재 단일 인스턴스에 retry/queue 설정이 없어서 백엔드 장애 시 데이터 유실 가능.

```yaml
# otel-collector-config.yml
exporters:
  otlp/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true
    retry_on_failure:
      enabled: true
      initial_interval: 5s
      max_interval: 30s
      max_elapsed_time: 300s
    sending_queue:
      enabled: true
      num_consumers: 10
      queue_size: 1000

  otlphttp/loki:
    endpoint: http://loki:3100/otlp
    retry_on_failure:
      enabled: true
      initial_interval: 5s
      max_elapsed_time: 300s
    sending_queue:
      enabled: true
      queue_size: 1000
```

쿠버네티스 환경이라면 Collector 를 `replicas: 2` 이상으로 설정해 HA 구성.

---

### 인증

```yaml
# Grafana — 익명 접근 비활성화
GF_AUTH_ANONYMOUS_ENABLED: "false"
GF_AUTH_DISABLE_LOGIN_FORM: "false"
GF_SECURITY_ADMIN_PASSWORD: "강한패스워드"  # 기본값 admin 변경 필수
```

내부망이라도 Grafana 는 최소한 로그인 인증을 걸어야 한다.
사용자가 많다면 LDAP 또는 OAuth(Google, GitHub 등) 연동을 권장한다.

---

## 3단계 — 성숙화

### 알림 설정

Grafana Alert Rules 로 주요 임계값 초과 시 알림을 보낸다.

권장 알림 항목:
- 에러율 급등 (5xx 비율 > 1%)
- 응답 시간 급증 (P99 > 3s)
- JVM 힙 사용률 > 85%
- 로그 유입량 이상 급증
- OTel Collector 드롭 발생

```yaml
# Grafana 알림 채널 예시 (Slack)
GF_UNIFIED_ALERTING_ENABLED: "true"
```

---

### Grafana 대시보드 프로비저닝

운영 투입 전 기본 대시보드를 코드로 관리해두면 Grafana 를 재시작해도 유지된다.
`infra/grafana/dashboards/` 에 JSON 파일로 추가하면 자동 로드된다.

유용한 공개 대시보드 (Grafana 공식 갤러리에서 ID 로 임포트 가능):
- JVM 모니터링: `4701`
- Spring Boot: `12900`
- OTel Collector: `15983`

---

## 쿠버네티스 운영 시 추가 고려사항

### DaemonSet + Gateway 2-tier 구성

```
앱 Pod → DaemonSet Collector (노드당 1개)
              └──► Gateway Collector (Deployment, 중앙)
                        └──► Loki / Tempo / Prometheus
```

- DaemonSet: 로컬 수집, 기본 필터링만 담당. 백엔드 인증 정보 없음
- Gateway: 취합, 샘플링, 라우팅, 인증 정보 집중 관리
- 앱은 `localhost` 로 쏘면 끝. 백엔드 변경 시 앱 무관

### Helm 차트 활용

Loki / Tempo / Grafana 는 공식 Helm 차트가 잘 갖춰져 있다.
compose 설정을 그대로 k8s 로 옮기기보다 Helm 차트 기반으로 재구성하는 게
운영 편의성(업그레이드, 설정 관리, 스케일링)이 훨씬 높다.

```bash
helm repo add grafana https://grafana.github.io/helm-charts
helm install loki grafana/loki -f values-loki.yaml
helm install tempo grafana/tempo -f values-tempo.yaml
helm install grafana grafana/grafana -f values-grafana.yaml
```

---

## ELK 와의 역할 분리 (병행 운영 시)

두 스택을 동시에 운영한다면 용도를 명확히 분리해야 중복 비용을 막을 수 있다.

| 용도 | 스택 | 이유 |
|---|---|---|
| 서비스 운영 모니터링, 트레이스 연동 | **Loki + Tempo** | 낮은 리소스, 시간 기반 조회 |
| 보안 감사 로그 (로그인, 권한 변경 등) | **Elasticsearch** | 필드 기반 검색, 장기 보관 |
| 비즈니스 분석, 클릭스트림 | **Elasticsearch** | 복잡한 집계, 풀텍스트 검색 |

Loki 로 운영 로그를 이전하고 ES 는 검색이 꼭 필요한 데이터만 남기면
ES 클러스터 사이즈를 줄일 수 있고 운영 비용이 의미 있게 낮아진다.

---

## 서버 구성 예시 (온프레미스 단일 클러스터)

```
[스토리지 서버]  대용량 디스크
  └── MinIO

[관측 서버]
  ├── Loki
  ├── Tempo
  ├── Grafana
  ├── Prometheus
  └── OTel Collector (Gateway)

[앱 서버들]
  ├── OTel Collector (DaemonSet or sidecar)
  └── Spring Boot 서비스
```

MinIO 는 디스크 I/O 가 집중되므로 다른 컴포넌트와 분리하는 것을 권장한다.
