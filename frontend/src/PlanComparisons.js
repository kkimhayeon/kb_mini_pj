const MATCH = { NAME_MATCH: '대상명 일치 후보', UNMATCHED: '연결할 계획 미확인', AMBIGUOUS: '동일 대상명 계획 여러 개' };
const STATUS = { DIFFERENCES: '금액·일정 차이 확인', NO_DIFFERENCE: '비교 항목 차이 없음', INSUFFICIENT: '추가 확인 필요', HISTORICAL: '기존 확인 이전 공시' };
export default function PlanComparisons({ comparisons = [] }) {
  if (!comparisons.length) return null;
  return (
    <section className="plan-comparisons" aria-label="계획별 금액 및 일정 비교">
      <h2>계획별 공시 비교</h2>
      {comparisons.map((item, index) => (
        <article className="reason-box" key={`${item.planId || 'unlinked'}-${item.evidence?.url}-${index}`}>
          <h3>{item.projectName || '대상명 미확인'}</h3>
          <p>{MATCH[item.matchStatus]} · {STATUS[item.comparisonStatus]}</p>
          <p>{item.message}</p>
          {!!item.changes?.length && (
            <table>
              <thead><tr><th>항목</th><th>RM 확인 정보</th><th>공시 정보</th></tr></thead>
              <tbody>{item.changes.map((change) => (
                <tr key={change.field}><td>{change.field === 'AMOUNT' ? '금액' : '일정'}</td>
                  <td>{change.before}</td><td>{change.after}</td></tr>
              ))}</tbody>
            </table>
          )}
          {item.evidence?.url && <a href={item.evidence.url} target="_blank" rel="noreferrer">{item.evidence.title}</a>}
          {item.evidence?.details?.sourceField && <p className="hint">근거 항목: 공시의 양수 대상·양수금액·양수일</p>}
        </article>
      ))}
    </section>
  );
}
