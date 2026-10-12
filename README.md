# Corporate Client Gap Agent

RM 사전정보와 OpenDART 공개정보를 비교해 투자·자금조달·해외사업 계획의 변경 가능성을 찾는 MVP입니다. 사실과 Gap 판정은 규칙 기반이며, Gemini는 판정된 Gap과 실제 근거만 입력받아 설명과 상담 질문을 보조 생성합니다.

## 현재 구현 현황

| 기능 | 상태 | 구현 |
| --- | --- | --- |
| 기업 선택 및 RM Mock 조회 | 완료 | 기존 `RM_Prior_Knowledge_100.csv` |
| OpenDART 기업 기본정보 조회 | 완료 | 기업코드 목록에서 회사명으로 매칭 후 기존 기업조회 API 사용 |
| OpenDART 재무·공시 조회 | 완료 | 최근 연간 재무제표(CFS 우선, OFS 대체) 및 최근 1년 공시 |
| 데이터 표준화 | 완료 | 매출, 영업이익, 단기차입금, 영업현금흐름 및 공시 근거 DTO |
| Gap Engine 및 3개 Gap 규칙 | 완료 | 투자, 자금조달, 해외사업 |
| Agent 조사 선택·질문 생성 | 완료 | RM 정보에 따라 재무·공시 조회를 선택하고 규칙별 질문 생성 |
| Gemini 설명·질문 생성 | 완료 | 사실 판정은 규칙 엔진에 두고, 근거 기반 설명과 상담 질문만 Gemini에서 생성 |
| 근거 및 결과 화면 | 완료 | OpenDART 공시 링크·재무 근거, React 결과 화면 |
| 테스트 | 완료 | 3개 Gap 시나리오와 조사 선택, Frontend 흐름 테스트 |

## 실행 방법

필수 환경: JDK 21 이상, Node.js/npm, OpenDART 인증키, Gemini API 키. Gradle은 설치된 JDK를 사용하고 Java 21 호환 코드로 컴파일합니다. 프론트엔드는 Node.js/npm과 기존 `frontend/node_modules`를 사용합니다.

저장소 루트의 `.env`에 `DART_API_KEY`와 `GEMINI_API_KEY`를 설정합니다. Spring Boot는 backend 실행 경로 기준으로 이 파일을 선택적으로 읽습니다. 키를 소스 코드나 Git에 추가하지 마세요. Gemini 키가 비어 있거나 호출·응답 해석이 실패하면 규칙 기반 설명·질문을 사용합니다. 호출 실패 여부는 결과 화면에 표시하며 확인된 근거와 규칙 판정은 유지합니다.

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

프론트엔드 개발 서버는 `frontend/package.json`의 proxy 설정을 통해 `/api` 요청을 `http://localhost:8080` 백엔드로 전달합니다. 별도 프론트엔드 `.env` 파일은 필요하지 않습니다. 백엔드 기업 목록은 `GET /api/companies`, 분석은 `GET /api/companies/{companyId}/analysis`에서 제공하며 프론트엔드는 이 API 경로와 JSON 응답 필드를 사용합니다. 브라우저에서 `http://localhost:3000`을 열고 기업을 선택한 다음 **최신정보 분석**을 누릅니다. CSV 경로는 루트 `.env`의 `RM_DATA_PATH`로 변경할 수 있습니다.

## 테스트

```powershell
Set-Location backend\springboot
.\gradlew.bat test

Set-Location ..\..\frontend
npm test -- --watchAll=false --runInBand
```

실제 OpenDART 및 Gemini 호출은 유효한 API 키와 네트워크 연결이 필요합니다. Gemini에는 Gap 종류, 기존 계획, 규칙 엔진이 확인한 최신 정보와 근거만 전달합니다. 재무수치와 공시 사실은 Gemini가 생성하거나 수정하지 않습니다. API 응답에 재무·공시 정보가 없을 경우 해당 사실을 결과에 표시하며, 근거가 없는 Gap은 만들지 않습니다.
