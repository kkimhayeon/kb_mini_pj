export const DOMAIN_LABELS = {
  INVESTMENT: '투자',
  FUNDING: '자금조달',
  FX: '해외사업',
};

export const GAP_LABELS = {
  INVESTMENT_PLAN_GAP: '투자계획 변경 후보',
  FUNDING_PLAN_GAP: '자금조달 변경 후보',
  FX_BUSINESS_GAP: '해외사업 변경 후보',
};

export const CHANGE_LABELS = {
  CONFLICT: '기존 정보와 상충',
  NEEDS_CONFIRMATION: '추가 확인 필요',
  SPECIFIED: '계획 구체화',
  NEW_INFO: '신규 정보',
};

export const STATUS_LABELS = {
  CONFIRMED: '근거 확인',
  PENDING: '상담 확인 필요',
  NO_CHANGE_EVIDENCE: '변경 근거 없음',
  RETRIEVAL_FAILED: '조회 실패',
};

export const STEP_LABELS = ['기업 입력', '정보 비교', '변경·질문', '답변 저장'];

export const COMPANY_INPUT_LABELS = {
  title: '분석할 기업을 입력해 주세요',
  field: '기업명',
  placeholder: '예: 동화약품, KR모터스',
  loading: '분석 중',
  submit: '분석 시작',
  hint: '입력한 기업을 DB에서 찾은 뒤 기존 정보와 OpenDART API 정보를 함께 조회합니다.',
};
