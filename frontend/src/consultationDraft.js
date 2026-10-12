export const EMPTY_DRAFT = {
  answerText: '',
  scope: '',
  planStatus: 'UNKNOWN',
  amount: '',
  currency: 'KRW',
  expectedAt: '',
  expectedPrecision: 'DAY',
  confirmed: false,
};

export const PLAN_STATUSES = {
  UNKNOWN: '미확인 / 기존 상태 유지',
  REVIEW: '검토 중',
  PLANNED: '예정',
  IN_PROGRESS: '진행 중',
  COMPLETED: '완료',
  CANCELED: '취소',
  NOT_APPLICABLE: '계획 없음',
};

export function hasPlanFields(draft) {
  return Boolean(draft.scope?.trim() || (draft.planStatus && draft.planStatus !== 'UNKNOWN')
    || draft.amount?.trim() || draft.expectedAt);
}

export function buildConsultationQuestions(questions, drafts, now) {
  return questions.map((question) => {
    const draft = { ...EMPTY_DRAFT, ...drafts[question.key] };
    const answerText = draft.answerText.trim();
    const hasFact = hasPlanFields(draft);
    if (hasFact && !answerText) {
      throw new Error('계획 정보를 입력한 질문에는 답변 원문도 입력해주세요.');
    }
    if (hasFact && !question.planId) {
      throw new Error('계획 정보를 저장하려면 질문에 연결된 계획이 필요합니다.');
    }
    if (draft.amount.trim() && !/^\d{1,18}(\.\d{1,2})?$/.test(draft.amount.trim())) {
      throw new Error('금액은 0 이상의 숫자로, 정수 18자리와 소수 2자리 이내로 입력해주세요.');
    }
    if (draft.amount.trim() && !/^[A-Z]{3}$/.test(draft.currency)) {
      throw new Error('통화는 KRW, USD와 같은 영문 대문자 3자리로 입력해주세요.');
    }
    if (!Object.hasOwn(PLAN_STATUSES, draft.planStatus)) {
      throw new Error('계획 상태를 확인해주세요.');
    }
    return {
      planId: question.planId,
      questionSource: 'AI_GENERATED',
      questionText: question.questionText,
      answer: answerText ? {
        answerStatus: 'ANSWERED',
        answerText,
        answeredAt: now,
        answeredBy: '고객',
        planFact: hasFact ? {
          planId: question.planId,
          domain: question.domain,
          scope: draft.scope.trim() || null,
          planStatus: draft.planStatus,
          // Keep the decimal as text to avoid JavaScript rounding large amounts.
          amount: draft.amount.trim() || null,
          currency: draft.amount.trim() ? draft.currency : null,
          expectedAt: draft.expectedAt || null,
          expectedPrecision: draft.expectedAt ? draft.expectedPrecision : null,
          extractionMethod: 'RM_ENTERED',
          verificationStatus: draft.confirmed ? 'RM_CONFIRMED' : 'PENDING',
          rmConfirmed: draft.confirmed,
          effectiveFrom: now,
          note: 'Gap 분석 후속 상담에서 입력한 계획 항목',
        } : null,
      } : null,
    };
  });
}
