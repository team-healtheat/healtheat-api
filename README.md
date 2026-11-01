# HealthEat API

Java 17, Spring Boot 3, PostgreSQL, Flyway 기반 초기 프로젝트입니다.

## 로컬 실행 (PowerShell)

1. Docker Desktop을 실행하고 `Copy-Item .env.example .env`로 설정 파일을 만든 뒤 비밀번호를 변경합니다.
2. `docker compose up -d`로 PostgreSQL을 시작합니다. 데이터는 Docker 볼륨에 유지됩니다.
3. 애플리케이션 프로세스에 DB 설정을 전달합니다. Compose의 `.env`는 Java 프로세스에 자동 전달되지 않습니다.

```powershell
$env:DB_PASSWORD = '설정한 비밀번호'
$env:DB_USERNAME = 'healtheat'
$env:DB_URL = 'jdbc:postgresql://localhost:5432/healtheat'
.\gradlew.bat bootRun
```

DB 이름, 사용자 또는 포트를 변경했다면 JDBC URL과 사용자도 일치시킵니다. Flyway가 빈 DB에 스키마를 적용하며 Hibernate는 스키마를 변경하지 않습니다. Swagger UI는 http://localhost:8080/swagger-ui.html, OpenAPI는 http://localhost:8080/v3/api-docs 에서 볼 수 있습니다. 테스트는 `.\gradlew.bat test`로 실행합니다. 통합 테스트에는 위 DB 환경변수가 필요합니다.

보안은 기본 차단과 BCrypt, CSRF 보호만 구성되어 있습니다. 회원가입, 로그인, JWT 발급·검증 및 CSRF 토큰 제공 흐름은 아직 구현되지 않았습니다. Swagger 쿠키 이름은 임시값입니다. 운영 전 HTTPS, Secure/HttpOnly 쿠키, SameSite, CORS와 CSRF 통합 검증이 필요합니다. 운영 DB의 백업·복구 정책도 아직 마련되지 않았습니다.

DB 모델은 [데이터 모델](https://app.notion.com/p/40b91ff3ce358165958ecd44d52cd205), [API 계약](https://app.notion.com/p/40b91ff3ce35816eb3ceda04cc2327ec), [실행 계획](https://app.notion.com/p/40b91ff3ce3581aaadfffc9299dd8b3a)을 기준으로 합니다. 알레르기 전체 상태, 직접 기록 정책 등 미결 사항은 후속 설계가 필요합니다.
