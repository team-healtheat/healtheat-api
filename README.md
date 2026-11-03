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

## 건강 프로필 온보딩

로그인 및 CSRF 토큰 발급 후 `POST /api/health-profiles/analyze`에 `{ "rawText": "..." }`를 보내면 확인할 후보가 반환됩니다. 분석 단계에서는 건강 요인이나 프로필을 저장하지 않습니다. 입력 내용은 OpenAI API에 전송되므로 실제 사용자 정보를 사용하기 전에 관련 고지와 동의를 준비해야 합니다. 지원 여부는 AI 응답이 아니라 DB 마스터를 기준으로 하며, 매칭되지 않는 항목은 `UNSUPPORTED`로 표시합니다. 모델의 결과는 진단이 아니며 사용자가 확인·수정해야 합니다.

로컬에서 실제 분석을 활성화하려면 애플리케이션을 실행하는 PowerShell에 다음 환경변수를 설정합니다. 비밀키는 저장소에 넣지 않습니다. 키를 설정하지 않거나 `AI_CHAT_MODEL=none`이면 서버는 실행되지만 분석 요청은 503을 반환합니다.

```powershell
$env:AI_CHAT_MODEL = 'openai'
$env:OPENAI_API_KEY = '발급받은 API 키'
# OPENAI_MODEL을 생략하면 gpt-6-luna 사용
```

확인한 전체 목록을 `PUT /api/health-profiles`에 `{ "factors": [{ "factorId": null, "name": "혈압 관리", "type": "GOAL", "reportedStatus": "REPORTED", "originalText": "혈압을 관리하고 싶어요", "active": true }] }` 형태로 전송합니다. `factorId`는 분석 결과에서 제공된 경우 유지하고, 없는 새 항목은 null로 보냅니다. `type`과 `reportedStatus`의 구체적 값은 프론트 연동 전에 합의할 임시 문자열 계약입니다. 지원 여부는 요청에서 받지 않습니다. 기존 항목 중 목록에 없는 항목은 삭제하지 않고 비활성화합니다. 빈 목록은 기존 항목을 모두 비활성화합니다. `GET /api/health-profiles`는 로그인한 사용자 자신의 활성·비활성 항목을 반환합니다. 두 변경 요청은 `X-XSRF-TOKEN`과 쿠키가 필요합니다.

AI 응답 형식이나 필수값이 잘못되면 502로 처리하며 저장하지 않습니다. 이 단계에서는 외부 호출을 자동 재시도하지 않습니다. 외부 호출은 연결 5초·응답 45초 제한을 사용하며 타임아웃은 504로 구분합니다. 실제 모델 제공 상태·비용·응답 품질과 타임아웃 값은 배포 전에 검증해야 합니다. GPT-6 Luna의 기본 추론 설정에서는 Spring AI의 temperature 및 top_p를 전송하지 않습니다.

## 선호 및 알레르기 설정

인증 후 `GET /api/users/me/preferences`로 내 설정을 읽고, CSRF 토큰과 쿠키를 포함한 `PATCH /api/users/me/preferences`로 변경할 수 있습니다. 제공한 필드만 바뀌며 `likedFoods`, `dislikedFoods`, `dietaryPreferences`는 문자열 목록을 전체 교체합니다. 빈 목록은 해당 목록을 비웁니다. `allowDiningOut`을 아직 선택하지 않았으면 `null`입니다.

알레르기 선택지는 없음(`NONE`)과 항목 있음(`PRESENT`) 두 가지입니다. 입력 전의 내부 상태 `UNANSWERED`는 선택지가 아니며 알레르기 없음으로 간주하지 않습니다. 기존 `UNKNOWN`은 입력 전 상태로 이전하고 기존 항목은 보존합니다. 변경할 때 `allergyStatus`와 `allergens`를 함께 보내며, `PRESENT`는 한 개 이상의 항목이 필요하고 `NONE`은 빈 목록을 보냅니다.

```json
{
  "allowDiningOut": true,
  "likedFoods": ["두부", "사과"],
  "allergyStatus": "PRESENT",
  "allergens": ["땅콩"]
}
```

온보딩 화면은 사용자가 입력을 최종 확인한 뒤 저장 요청을 보내야 합니다. 이 API는 알레르기 항목을 저장할 뿐 음식의 안전성을 보장하지 않습니다. 기존 알레르기 항목은 상태 마이그레이션 시 삭제하지 않으며, 새 전체 상태는 미응답으로 시작하므로 사용자의 재확인이 필요합니다.

## 기본 정보

인증된 사용자는 `GET /api/users/me/profile`로 나이와 선택 사항인 성별을 조회하고, CSRF 토큰과 인증 쿠키를 포함한 `PUT /api/users/me/profile`로 저장할 수 있습니다. PUT은 두 값을 전체 교체하며 `null` 또는 생략은 해당 항목을 지웁니다. 나이는 현재 DB 모델의 정수 나이만 지원하며 연령대와 성별 선택지는 아직 정하지 않았습니다. 외식·배달 허용 여부는 선호 설정 API에서 관리합니다.

## 저장된 오늘 식단 조회

`GET /api/meal-plans/today`는 인증된 사용자의 서버 날짜 기준 저장된 식단과 현재 추천만 조회합니다. 데이터가 없으면 204를 반환하며 AI를 호출하거나 식단을 생성하지 않습니다. 시간대 정책은 배포 전에 확정해야 합니다. `POST /api/meal-plans/generate`는 조건이 확인된 경우 AI 식단을 생성·검증·저장하며 기존 식단이 있으면 재사용합니다. 레시피가 있는 음식은 재료와 조리 순서를 함께 반환합니다. 사용자가 확인한 활성 건강 요인은 LLM에 전달하지만 검증된 건강 지침은 적용되지 않으며 생성 식단의 의학적 적합성은 보장하지 않습니다. 저장된 알레르기와 응답 상태를 AI에 전달합니다. 서버에서 음식 재료나 동의어를 대조하지 않으며 외식·배달도 일반 설정에 따라 허용합니다. 입력 전 상태를 없음으로 단정하지 않습니다. AI의 결과는 알레르기 안전을 보장하지 않으며 외식·배달의 재료와 교차접촉은 확인되지 않습니다. `POST /api/meal-plans/{planId}/meals/{mealType}/regenerate`는 해당 끼니만 새로 추천하고 이전 추천을 보존합니다. 2026-11-03 테스트용 PostgreSQL과 가상 사용자 정보로 실제 OpenAI 세 끼 생성·저녁 재추천·재조회를 각각 확인했습니다. 이는 응답 형식과 저장 흐름의 확인이며 건강·알레르기 안전성을 보장하지 않습니다.

온보딩의 알레르기 선택 항목은 프론트엔드에서 관리합니다. 목록에 없는 항목은 직접 입력해 선택 항목과 함께 저장할 수 있습니다. 없음은 빈 목록과 함께 전송합니다.

실제 OpenAI 호출을 포함한 선택적 통합 테스트는 `RUN_LIVE_MEAL_TEST=true`, `AI_CHAT_MODEL=openai`와 별도 테스트 DB 및 환경변수 API 키를 설정한 뒤 `LiveMealGenerationTests`를 실행합니다. 실제 API 사용료가 발생하므로 기본 테스트에서는 실행하지 않습니다.

인증 후 `GET /api/recipes/{recipeId}`로 본인의 식단에 저장된 레시피 상세(재료, 조리 순서, 인분, 시간)를 조회할 수 있습니다. 본인의 과거 재추천 버전도 조회할 수 있으며, 다른 사용자의 레시피나 존재하지 않는 ID는 404를 반환합니다. 레시피가 없는 바로 먹는 음식에는 레시피 ID가 없습니다.

## 실제 식사 기록

- `POST /api/meal-records/from-recommendation`: `{ "recommendationId": 123 }`으로 본인의 추천 한 끼를 기록합니다. 같은 추천을 다시 요청하면 기존 기록을 반환하며, 추천의 음식명을 복사하되 섭취량은 임의로 기록하지 않습니다.
- `POST /api/meal-records`: `{ "mealDate": "2026-11-03", "mealType": "DINNER", "sourceType": "HOME_COOKED", "items": [{ "foodName": "김치찌개", "servingDescription": "반 그릇" }] }`처럼 직접 기록합니다. `sourceType`과 `servingDescription`은 선택이며 직접 기록은 같은 끼니에도 여러 건 가능합니다. 끼니는 `BREAKFAST`, `LUNCH`, `DINNER`, 출처는 `HOME_COOKED`, `DINING_OUT`(배달 포함)을 사용합니다.
- `GET /api/meal-records?from=2026-11-01&to=2026-11-03`: 인증된 사용자의 해당 기간 기록을 양 끝 날짜를 포함해 조회합니다. 날짜를 생략하면 해당 경계 없이 조회합니다.

쓰기 요청에는 인증 쿠키와 CSRF 토큰이 필요합니다. 미기록은 식사를 하지 않았다는 의미가 아닙니다.
