# HealthEat API

Java 17, Spring Boot 3, PostgreSQL, Flyway 기반 초기 프로젝트입니다.

## 로컬 실행 (PowerShell)

1. Docker Desktop을 실행하고 `Copy-Item .env.example .env`로 설정 파일을 만든 뒤 비밀번호를 변경합니다.
2. `docker compose up -d`로 PostgreSQL을 시작합니다. 데이터는 Docker 볼륨에 유지됩니다.
3. 애플리케이션 프로세스에 DB 설정을 전달합니다. Compose의 `.env`는 Java 프로세스에 자동 전달되지 않습니다.

```powershell
$env:DB_PASSWORD = '설정한 비밀번호'
$env:DB_USERNAME = 'healtheat'
$env:JWT_SECRET = '32바이트 이상의 충분히 긴 무작위 비밀키'
$env:DB_URL = 'jdbc:postgresql://localhost:5432/healtheat'
.\gradlew.bat bootRun
```

DB 이름, 사용자 또는 포트를 변경했다면 JDBC URL과 사용자도 일치시킵니다. Flyway가 빈 DB에 스키마를 적용하며 Hibernate는 스키마를 변경하지 않습니다. Swagger UI는 http://localhost:8080/swagger-ui.html, OpenAPI는 http://localhost:8080/v3/api-docs 에서 볼 수 있습니다. 테스트는 `.\gradlew.bat test`로 실행합니다. 통합 테스트에는 위 DB 환경변수가 필요합니다.

인증은 이메일·비밀번호 가입 및 로그인, BCrypt 해시, 30분 JWT 쿠키를 사용합니다. JWT_SECRET은 32 UTF-8 바이트 이상의 비밀값으로 설정하며 저장소에 올리지 마세요. 모든 변경 요청 전에 `GET /api/auth/csrf`를 호출하고 응답의 `token` 값을 `X-XSRF-TOKEN` 헤더로 보냅니다. CSRF 쿠키도 함께 전송해야 합니다. 로그인 응답의 `access_token` 쿠키는 HttpOnly·Secure이며 HTTPS에서 사용합니다. 브라우저 요청은 credentials를 포함해야 합니다. Swagger에서는 CSRF 응답 토큰을 복사해 요청 헤더로 전달해야 합니다. 로컬 개발 기본 CORS는 localhost:3000, localhost:5173이며 `CORS_ALLOWED_ORIGINS`로 변경할 수 있습니다. 쿠키 SameSite 기본값은 Lax입니다.

로그아웃은 인증된 상태에서 CSRF 쿠키와 토큰 헤더를 포함해 `POST /api/auth/logout`을 호출합니다. 성공 시 서버가 로그인 때와 동일한 Path(`/`)와 Domain 정책(도메인 속성이 없는 host-only)으로 HttpOnly 쿠키를 만료합니다. 프론트엔드는 성공 응답을 받은 후 인증 상태를 초기화하고 로그인 화면으로 이동해야 합니다. Refresh Token, JWT 블랙리스트, 서버 측 토큰 무효화는 MVP에서 제외하므로 이미 발급된 JWT는 로그아웃 후에도 만료 전까지 최대 30분간 유효할 수 있습니다. 배포 전 실제 도메인에서 HTTPS, SameSite, CORS와 CSRF 흐름을 검증해야 합니다. 운영 DB의 백업·복구 정책도 아직 마련되지 않았습니다.
DB 모델은 [데이터 모델](https://app.notion.com/p/40b91ff3ce358165958ecd44d52cd205), [API 계약](https://app.notion.com/p/40b91ff3ce35816eb3ceda04cc2327ec), [실행 계획](https://app.notion.com/p/40b91ff3ce3581aaadfffc9299dd8b3a)을 기준으로 합니다. 알레르기 전체 상태, 직접 기록 정책 등 미결 사항은 후속 설계가 필요합니다.
