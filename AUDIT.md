# 보안 감사 로그 설계 가이드

---

## 무엇을 기록해야 하는가

```
1. 인증 이벤트    누가 로그인/로그아웃했는지
2. 인가 이벤트    접근이 거부됐는지
3. 데이터 접근    민감 데이터를 누가 조회했는지
4. 데이터 변경    생성/수정/삭제를 누가 했는지
5. 시스템 변경    설정 변경, 권한 변경, 관리자 행위
```

| 카테고리 | 이벤트 예시 | 필수 필드 |
|---|---|---|
| 인증 | 로그인 성공/실패, 로그아웃, 토큰 만료 | user, ip, result, attempt_count |
| 인가 | 권한 없는 리소스 접근 시도 | user, resource, required_role |
| 데이터 접근 | 개인정보 조회, 대량 export | user, resource, record_count |
| 데이터 변경 | 회원 정보 수정/삭제 | user, resource, before, after |
| 시스템 | 설정 변경, 관리자 계정 생성 | user, target, before, after |

---

## 서비스마다 전부 따로 찍어야 하나?

**아니다.** 레이어별로 역할을 분리하면 반복 코드 없이 전 서비스를 커버할 수 있다.

```
[Gateway Filter]      HTTP 레벨 전체 자동 처리 (이미 구현)
      │                 method, path, ip, user, status, duration
      │
[Spring AOP]          @Audit 애노테이션 하나로 전 서비스 공통 처리
      │                 메서드 단위 감사 — 한 번만 만들면 됨
      │
[Spring Security]     인증/인가 이벤트 자동 발행
      │                 AuthenticationSuccessEvent 등 — 리스너만 달면 됨
      │
[서비스 코드]          비즈니스 맥락이 필요한 것만 수동으로
                        대량 export, 관리자 권한 부여 등
```

---

## 구현 전략

### 1단계 — Gateway Filter (현재 구현 완료)

모든 HTTP 요청의 공통 정보를 자동 기록한다.

```java
// gateway/TraceFilter.java
log.info("audit",
    kv("audit",       true),
    kv("method",      method),   // GET, POST ...
    kv("path",        path),     // /api/users/123
    kv("status",      status),   // 200, 403, 500
    kv("ip",          ip),       // X-Forwarded-For 또는 remoteAddress
    kv("user",        user),     // X-User-Id 헤더 (없으면 anonymous)
    kv("duration_ms", duration),
    kv("result",      signal.name())
);
```

> `user` 필드는 현재 `X-User-Id` 헤더를 그대로 읽는 단순 구현이다.
> 실제 운영에서는 `resolveUser()` 를 JWT 파싱 또는 인증 서버 연동으로 교체한다.

---

### 2단계 — Spring Security 이벤트 리스너

Spring Security 를 쓰는 경우 인증/인가 이벤트가 자동으로 발행된다.
리스너를 하나만 만들면 전 서비스의 로그인 성공/실패, 접근 거부가 자동으로 잡힌다.

```java
@Component
@Slf4j
public class SecurityAuditListener {

    @EventListener
    public void onLoginSuccess(AuthenticationSuccessEvent event) {
        String user = event.getAuthentication().getName();
        log.info("audit",
            kv("audit",    true),
            kv("action",   "LOGIN_SUCCESS"),
            kv("user",     user)
        );
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        String user = event.getAuthentication().getName();
        log.warn("audit",
            kv("audit",    true),
            kv("action",   "LOGIN_FAIL"),
            kv("user",     user),
            kv("reason",   event.getException().getMessage())
        );
    }

    @EventListener
    public void onAccessDenied(AuthorizationDeniedEvent event) {
        log.warn("audit",
            kv("audit",    true),
            kv("action",   "ACCESS_DENIED"),
            kv("user",     event.getAuthentication().get().getName()),
            kv("resource", event.getAuthorizationDecision().toString())
        );
    }
}
```

---

### 3단계 — @Audit AOP (핵심)

**가장 중요한 단계다.**
`@Audit` 애노테이션을 한 번만 만들어두면, 메서드에 붙이기만 해도 자동으로 감사 로그가 찍힌다.
서비스마다 반복 코드를 쓸 필요가 없다.

#### 애노테이션 정의

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {
    String action();            // "GET_USER", "DELETE_ORDER" 등
    String resource() default ""; // "user", "order" 등
}
```

#### AOP Aspect

```java
@Aspect
@Component
@Slf4j
public class AuditAspect {

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint pjp, Audit audit) throws Throwable {
        String user = SecurityContextHolder.getContext()
            .getAuthentication().getName();
        long start = System.currentTimeMillis();

        try {
            Object result = pjp.proceed();
            log.info("audit",
                kv("audit",       true),
                kv("action",      audit.action()),
                kv("resource",    audit.resource()),
                kv("user",        user),
                kv("duration_ms", System.currentTimeMillis() - start),
                kv("result",      "SUCCESS")
            );
            return result;
        } catch (Exception e) {
            log.warn("audit",
                kv("audit",    true),
                kv("action",   audit.action()),
                kv("resource", audit.resource()),
                kv("user",     user),
                kv("result",   "FAIL"),
                kv("error",    e.getMessage())
            );
            throw e;
        }
    }
}
```

#### 사용 — 애노테이션만 붙이면 끝

```java
@Audit(action = "GET_USER_INFO", resource = "user")
public User getUser(Long userId) { ... }

@Audit(action = "DELETE_USER", resource = "user")
public void deleteUser(Long userId) { ... }

@Audit(action = "EXPORT_USER_LIST", resource = "user")
public List<User> exportUsers() { ... }
```

---

### 4단계 — 서비스 코드 수동 (필요한 것만)

AOP 로 잡기 어려운 비즈니스 맥락이 필요한 경우만 직접 찍는다.

```java
// 변경 전/후 값이 필요한 경우
User before = userRepository.findById(id);
userRepository.save(updated);

log.info("audit",
    kv("audit",    true),
    kv("action",   "UPDATE_USER_ROLE"),
    kv("resource", "user"),
    kv("target",   id),
    kv("before",   before.getRole()),
    kv("after",    updated.getRole()),
    kv("user",     currentUser)
);

// 대량 데이터 export
List<User> users = userRepository.findAll();
log.info("audit",
    kv("audit",        true),
    kv("action",       "EXPORT_ALL_USERS"),
    kv("resource",     "user"),
    kv("record_count", users.size()),
    kv("user",         currentUser)
);
```

---

## 감사 로그 표준 필드

모든 감사 로그는 아래 필드를 공통으로 포함하는 것을 권장한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `audit` | boolean | 감사 로그 여부 — Kibana/ES 필터링용 |
| `action` | keyword | 수행한 행위 (LOGIN_SUCCESS, DELETE_USER 등) |
| `user` | keyword | 행위자 ID |
| `ip` | keyword | 클라이언트 IP |
| `resource` | keyword | 대상 리소스 (user, order 등) |
| `result` | keyword | SUCCESS / FAIL / ACCESS_DENIED |
| `trace_id` | keyword | OTel 트레이스 ID — 요청 전체 흐름 추적 |

---

## 적용 순서 요약

```
1단계  Gateway Filter     완료 — 모든 HTTP 요청 자동 기록
2단계  Security 리스너    로그인/로그아웃/접근 거부 자동 기록
3단계  @Audit AOP         메서드 단위 선언적 감사 — 핵심
4단계  수동 로깅           변경 전후 값, 대량 export 등 맥락 필요한 것만
```

2~3단계를 공통 모듈로 한 번만 만들면
각 서비스는 `@Audit` 애노테이션만 붙이면 된다. 반복 코드 없음.

---

## Kibana / ES 활용

```kql
# 전체 감사 로그
audit: true

# 로그인 실패만
audit: true and action: "LOGIN_FAIL"

# 특정 유저 행동 전체 이력
audit: true and user: "user123"

# 권한 없는 접근 시도
audit: true and result: "ACCESS_DENIED"

# 민감 데이터 export 이력
audit: true and action: "EXPORT_*"

# 특정 trace_id 로 전체 흐름 추적
trace_id: "177cd5f7773b9bd77eeb950d65f5617b"
```
