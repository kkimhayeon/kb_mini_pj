# Corporate Client Gap Agent

RM 사전정보와 OpenDART 공개정보를 비교해 투자·자금조달·해외사업 계획의 변경 가능성을 찾는 MVP입니다. 사실과 Gap 판정은 규칙 기반이며, OpenAI는 판정된 Gap과 실제 근거만 입력받아 설명과 상담 질문을 보조 생성합니다.

## 현재 구현 현황

| 기능 | 상태 | 구현 |
| --- | --- | --- |
| 기업 선택 및 RM 조회 | 완료 | PostgreSQL `company` + `rm_plan`; 한 행에 하나의 계획 |
| OpenDART 기업 기본정보 조회 | 완료 | DB의 `corp_code`로 기업조회 API 사용 |
| OpenDART 재무·공시 조회 | 완료 | 계획별 확인일 기준 공시 목록·원문 및 최근 연간 재무제표 |
| 데이터 표준화 | 진행 | 계획 근거·정정 표시·조회 상태와 총차입금 조사 신호 |
| Gap Engine | 진행 | 직접 원문 근거만 변경으로 분류하고 상태·우선순위 산출 |
| Agent 조사 선택·질문 생성 | 완료 | RM 정보에 따라 재무·공시 조회를 선택하고 규칙별 질문 생성 |
| OpenAI 설명·질문 생성 | 완료 | 사실 판정은 규칙 엔진에 두고, 근거 기반 설명과 상담 질문만 OpenAI에서 생성 |
| 근거 및 결과 화면 | 완료 | OpenDART 공시 링크·재무 근거, React 결과 화면 |
| AI·DB 비교 대시보드 | 완료 | DART-only OpenAI 결과와 RM DB+DART 규칙 분석을 같은 수집자료로 나란히 비교 |
| 상담 이력 및 질문·답변 | 완료 | 직접 입력한 상담 내용과 AI 질문·답변을 각각 PostgreSQL에 저장·재조회 |
| OpenAI 토큰 절감 | 완료 | 모델과 프롬프트 SHA-256이 같은 생성 요청은 PostgreSQL 캐시에서 응답 재사용 (30일) |
| 테스트 | 완료 | 3개 Gap 시나리오와 조사 선택, Frontend 흐름 테스트 |

## 실행 방법

필수 환경: JDK 21 이상, Node.js/npm, 유효한 OpenDART 인증키. DART 분석만 사용할 경우 OpenAI API 키는 선택이며, AI 설명 및 DART-only 비교 결과가 필요할 때 설정합니다. Gradle은 설치된 JDK를 사용하고 Java 21 호환 코드로 컴파일합니다. 프론트엔드는 Node.js/npm과 기존 `frontend/node_modules`를 사용합니다.

저장소 루트 `.env`에 OpenDART에서 발급한 40자리 16진수 키를 `DART_API_KEY`로, 선택적인 OpenAI 키를 `OPEN_API_KEY`로 분리해 설정하고 PostgreSQL 접속정보(`DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`)를 입력합니다. `OPEN_API_KEY`는 OpenAI 전용이며 DART 인증에 사용하지 않습니다. OpenDART 응답 상태 `100`은 키 인증 오류이므로 키를 확인하세요. 메시지에 키가 노출된 경우 기존 키를 폐기하고 새 키를 발급해야 합니다. 비밀번호는 소스 코드나 Git에 추가하지 마세요.

### 로컬 PostgreSQL 및 DBeaver (Docker 불필요)

1. Windows용 PostgreSQL을 설치하고 서버 서비스를 실행합니다. 기본 포트는 `5432`입니다.
2. DBeaver에서 PostgreSQL 설치 때 지정한 사용자(기본값 `postgres`)로 `localhost:5432`에 연결한 뒤 데이터베이스를 만듭니다.

```sql
CREATE DATABASE kb_gap OWNER postgres;
```

3. 저장소 루트 `.env`에 아래 값을 설정합니다.

```dotenv
DB_NAME=kb_gap
DB_USERNAME=postgres
DB_PASSWORD=PostgreSQL_설치시_설정한_비밀번호
```

4. 백엔드를 실행하면 [`schema.sql`](./backend/springboot/src/main/resources/schema.sql)이 설계서의 핵심 5개 테이블(`company`, `rm_plan`, `gap_analysis`, `gap_result`, `analysis_evidence`), CSV 적재용 `rm_plan_import`, AI 응답 캐시, 비교 실행, 직접 입력 상담 이력, 상담 질문 및 답변 테이블을 생성합니다. 이미 테이블이 있는 DB에도 필요한 테이블과 코드 제약이 적용됩니다.
5. DBeaver에서 `rm_plan_import` 테이블을 우클릭해 **Import Data → CSV**를 선택합니다. 저장소 루트의 [`rm_plan_import.csv`](./rm_plan_import.csv)를 고르고 첫 행을 컬럼명으로 인식해 같은 이름끼리 매핑합니다. 가져오기 전에 재적재 중복을 피하려면 DBeaver SQL 편집기에서 `TRUNCATE TABLE rm_plan_import;`을 먼저 실행하세요. 현재 파일은 KRX 현행 상장법인 목록과 회사명이 일치하는 82개 기업의 246개 계획 행(23개 컬럼)입니다. RM 상담 내용은 DB 적재 흐름 검증용 가상 샘플이므로 실제 업무에서는 고객의 확인된 RM 데이터로 교체하세요.
6. DBeaver에서 [`import_rm_plans.sql`](./backend/springboot/src/main/resources/import_rm_plans.sql)을 열어 전체 실행합니다. 기업을 OpenDART `corp_code`로 upsert하고 계획을 `rm_plan`에 반영합니다. 기업별 `plan_id`는 `source_plan_key`로 저장해 같은 CSV를 다시 가져와도 중복 계획이 생기지 않습니다. 새 전체 목록에 없는 회사는 이력 보존을 위해 삭제하지 않고 `company.is_active = FALSE`로 전환해 앱의 활성 기업 선택 목록에서 제외합니다. 설계서의 코드로 매핑할 때 `FOREIGN_BUSINESS`는 `FX`, `EXPLICIT_NO`는 `EXPLICIT_NONE`, `UNKNOWN`은 `UNCONFIRMED`로 저장합니다.
7. DBeaver SQL 편집기에서 아래 쿼리로 적재 결과를 확인합니다.

```sql
SELECT c.company_id, c.corp_code, c.corp_name, p.domain, p.plan_id,
       p.knowledge_status, p.plan_status
FROM company c
JOIN rm_plan p ON p.company_id = c.company_id
WHERE c.is_active = TRUE
ORDER BY c.company_id, p.domain, p.plan_id;
```

기본 접속값은 `localhost:5432`, 데이터베이스 `kb_gap`, 사용자 `postgres`입니다. 실제 PostgreSQL 설치 때 지정한 사용자와 비밀번호에 맞춰 `.env`의 `DB_USERNAME`과 `DB_PASSWORD`를 설정하세요. 컨테이너나 Docker를 사용하지 않습니다. `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` 환경변수로 접속 설정을 바꿀 수 있습니다.

백엔드:

```powershell
Set-Location backend\springboot
.\gradlew.bat bootRun
```

프론트엔드(별도 PowerShell):

```powershell
Set-Location frontend
npm install
npm start
```

프론트엔드 개발 서버는 `frontend/package.json`의 proxy 설정을 통해 `/api` 요청을 `http://localhost:8080` 백엔드로 전달합니다. 별도 프론트엔드 `.env` 파일은 필요하지 않습니다. 백엔드 기업 목록은 `GET /api/companies`, 분석은 `GET /api/companies/{companyId}/analysis`에서 제공합니다. 앱 실행 전 PostgreSQL 서비스를 시작하고 CSV 데이터를 테이블에 적재하세요. 기업 화면의 **상담 내역 입력** 영역에서 상담일과 본문을 저장하면 `consultation_record`에 회사별 상담 이력으로 쌓이고, 최근 상담일도 갱신됩니다. 저장한 이력은 같은 화면 아래에서 다시 조회할 수 있습니다. 기업 화면의 **AI·DB 비교 분석**은 공통으로 수집한 OpenDART 자료를 DART-only OpenAI 분석과 RM 계획 DB+DART 규칙 분석에 각각 제공해 비교 이력을 저장합니다. AI-only 분석 프롬프트에는 RM 상담정보를 넣지 않습니다. 분석 질문은 분석 실행 때 자동 저장되고 각 질문 아래 답변을 입력해 저장할 수 있습니다. 최신 비교 결과와 저장된 답변은 기업을 다시 선택할 때 불러옵니다.

OpenAI의 응답 캐시는 전체 프롬프트를 저장하지 않고 모델명과 프롬프트의 SHA-256 키, 생성 응답 JSON만 DB에 저장합니다. 같은 입력은 30일간 캐시를 사용하므로, 새 DART 정보나 RM 계획으로 프롬프트가 달라지면 새 OpenAI 호출이 발생합니다. OpenAI 키를 설정하지 않으면 규칙 템플릿 질문으로 분석하며 DART-only AI 요약은 생성하지 않습니다. 직접 입력 상담 이력 조회·저장은 각각 `GET/POST /api/companies/{companyId}/consultations`이며, 기존 `/api/companies/{companyId}/analysis` 분석 API도 유지됩니다. 비교 실행은 `POST /api/companies/{companyId}/comparison`, 최신 저장 결과 조회는 `GET /api/companies/{companyId}/comparison/latest`, 질문 답변 저장은 `PUT /api/companies/questions/{questionId}/answer`입니다.

## 테스트

```powershell
Set-Location backend\springboot
.\gradlew.bat test

Set-Location ..\..\frontend
npm test -- --watchAll=false --runInBand
```

실제 OpenDART 호출은 유효한 OpenDART 키(`DART_API_KEY`)와 네트워크 연결이 필요합니다. OpenAI 설명과 DART-only 비교 결과도 사용하려면 유효한 OpenAI 키(`OPEN_API_KEY`)가 필요합니다. 조회한 공시 제목만으로 변경을 확정하지 않으며, 원문 근거가 없거나 조회에 실패하면 변경 근거 미발견·판단 보류·조회 실패를 구분합니다. 재무 변화는 직접 공시 근거가 확인되기 전까지 조사 신호로만 표시합니다. 분석 후 항목별 유효성 상태를 PostgreSQL에 기록합니다.