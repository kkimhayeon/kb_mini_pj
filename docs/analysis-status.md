# 분석 범위와 상태 (3단계)

분석 API는 기존 결과에 outcome, sources, scopeLimited, retrievalStatus를 함께 반환합니다.

- GAPS_FOUND: 확인된 자료에서 변경 후보가 발견됨. 일부 조회에 실패했어도 근거가 있는 결과는 유지.
- NO_CHANGE_EVIDENCE: 이번 조회 범위와 적용 규칙에서 변경 근거를 찾지 못함. 실제 변경이 없다는 확정이 아님.
- INCONCLUSIVE: 조회 실패, 잘린 공시 목록, 필요한 재무 항목 누락 등으로 판단이 제한됨.
- NO_DATA: 정상 조회했지만 분석에 사용할 공개정보가 없음.

sources에는 COMPANY, FINANCIALS, DISCLOSURES 각각의 SUCCESS, NO_DATA, NOT_REQUESTED, FAILED, PARTIAL 상태와 사용자 안내를 담습니다. 조사 규칙상 요청하지 않은 자료는 실패로 표시하지 않습니다.

retrievalStatus는 기존 DB 허용값인 SUCCESS, PARTIAL_FAILURE, FAILURE를 사용합니다. 정상적인 자료 없음은 SUCCESS이지만 scopeLimited=true입니다. 상태 상세와 outcome은 crm_snapshot에, scopeLimited는 scope_limited에 저장합니다. 추가 스키마 변경은 필요하지 않습니다.

Gemini는 보조 설명 생성이므로 호출 실패 또는 잘못된 응답이 공개정보 조회 실패를 의미하지는 않습니다. 실패한 항목의 설명·질문은 규칙 기반으로 대체하고 explanationSource를 'Rule fallback (Gemini failed)'로 반환합니다. 키 미설정에 의한 기본 문구 사용과 구분합니다. 외부 요청은 연결 10초, OpenDART 응답 20초, Gemini 응답 30초 제한을 둡니다. 여러 외부 요청 전체의 합산 시간 제한은 아닙니다.

4단계부터 최근 1년의 최종보고서 공시 목록을 끝 페이지까지 수집합니다. 페이지 실패·누락·조회 제한은 PARTIAL로 표시합니다. 구조화된 상세 항목 조회가 불완전하면 DISCLOSURE_DETAILS=PARTIAL을 추가합니다. 지원되는 양수 공시와 재무 비교 기준은 [공시 수집과 계획별 비교](disclosure-comparison.md)를 참고하세요. 공시 본문 전체를 검토했다는 의미는 아닙니다.

기업 확인 자체에 실패하면 재무·공시를 조회하지 않고 실패 상태를 기록합니다. DB 저장 실패는 분석 성공으로 숨기지 않고 기존 오류 응답으로 전달합니다. 상담 이력 조회 실패는 분석 결과와 별도로 화면에 표시합니다.

검증은 모의 외부 응답, 로컬 HTTP 테스트 서버, 기존 상담 저장용 H2 테스트 DB로 수행합니다. 실제 OpenDART/Gemini 및 PostgreSQL 운영 연결을 호출하는 테스트는 아닙니다.

