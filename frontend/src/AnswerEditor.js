import { EMPTY_DRAFT, hasPlanFields, PLAN_STATUSES } from './consultationDraft';

export default function AnswerEditor({ question, number, domainLabel, draft, disabled, onChange }) {
  const value = { ...EMPTY_DRAFT, ...draft };
  const update = (field, next) => onChange({
    ...value,
    [field]: next,
    confirmed: field === 'confirmed' ? next : false,
  });
  const inputId = (field) => `${question.key}-${field}`;

  return (
    <article className="answer-item">
      <div className="answer-question">
        <span>{domainLabel} 질문 {number} · {question.displayLabel}</span>
        <p>{question.questionText}</p>
      </div>
      <fieldset className="answer-fields" disabled={disabled}>
        <label htmlFor={inputId('answer')}>답변 원문</label>
        <textarea
          id={inputId('answer')}
          value={value.answerText}
          onChange={(event) => update('answerText', event.target.value)}
          placeholder="고객 답변을 입력해주세요"
          rows="4"
        />
        {question.planId ? (
          <details className="plan-review">
            <summary>계획 정보 입력 및 RM 확인 (선택)</summary>
            <p className="hint">확인한 항목만 입력하세요. 금액과 일정은 다른 답변에서 나누어 입력해도 같은 계획에 합쳐집니다.</p>
            <div className="plan-review-grid">
              <label htmlFor={inputId('scope')}>사업 대상·범위
                <input id={inputId('scope')} value={value.scope}
                  onChange={(event) => update('scope', event.target.value)} placeholder="예: A공장 설비 증설" />
              </label>
              <label htmlFor={inputId('status')}>계획 상태
                <select id={inputId('status')} value={value.planStatus}
                  onChange={(event) => update('planStatus', event.target.value)}>
                  {Object.entries(PLAN_STATUSES).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
                </select>
              </label>
              <label htmlFor={inputId('amount')}>금액 (선택한 통화 단위)
                <input id={inputId('amount')} inputMode="decimal" value={value.amount}
                  onChange={(event) => update('amount', event.target.value)} placeholder="예: 10000000000" />
              </label>
              <label htmlFor={inputId('currency')}>통화
                <input id={inputId('currency')} value={value.currency} maxLength={3}
                  onChange={(event) => update('currency', event.target.value.toUpperCase())} />
              </label>
              <label htmlFor={inputId('expected')}>예정일
                <input id={inputId('expected')} type="date" value={value.expectedAt}
                  onChange={(event) => update('expectedAt', event.target.value)} />
              </label>
              <label htmlFor={inputId('precision')}>일정의 확정 범위
                <select id={inputId('precision')} value={value.expectedPrecision}
                  onChange={(event) => update('expectedPrecision', event.target.value)}>
                  <option value="DAY">일 단위</option>
                  <option value="MONTH">월 단위</option>
                  <option value="QUARTER">분기 단위</option>
                  <option value="HALF">반기 단위</option>
                  <option value="YEAR">연 단위</option>
                </select>
              </label>
            </div>
            <label className="confirmation-check" htmlFor={inputId('confirmed')}>
              <input id={inputId('confirmed')} type="checkbox" checked={value.confirmed}
                disabled={!value.answerText.trim() || !hasPlanFields(value)}
                onChange={(event) => update('confirmed', event.target.checked)} />
              입력한 계획 항목을 RM이 확인했으며 다음 분석에 반영합니다
            </label>
            <p className="hint">확인하지 않은 계획 정보는 미확인 상태로 저장됩니다. 답변이나 항목을 수정하면 다시 확인해야 합니다.</p>
          </details>
        ) : <p className="hint">연결된 계획이 없어 답변 원문만 저장됩니다.</p>}
      </fieldset>
    </article>
  );
}
