const SOURCE_LABELS = { COMPANY: '기업 확인', FINANCIALS: '재무정보', DISCLOSURES: '공시정보', DISCLOSURE_DETAILS: '공시 상세 항목' };
const STATE_LABELS = {
  SUCCESS: '조회 성공', NO_DATA: '자료 없음', NOT_REQUESTED: '이번 조사 대상 아님',
  FAILED: '조회 실패', PARTIAL: '일부 자료만 조회',
};

export function outcomeLabel(outcome) {
  return {
    GAPS_FOUND: '변경 후보 확인',
    NO_CHANGE_EVIDENCE: '조회 범위 내 변경 근거 없음',
    INCONCLUSIVE: '자료 부족으로 판단 제한',
    NO_DATA: '분석할 공개정보 없음',
  }[outcome] || '자료 확인 필요';
}

export default function AnalysisStatus({ analysis }) {
  if (!analysis.outcome && !analysis.sources?.length) return null;
  const fallback = analysis.gaps?.some((gap) => gap.explanationSource === 'Rule fallback (Gemini failed)');
  return (
    <section className="analysis-status" aria-label="분석 범위와 조회 상태">
      <p><strong>{outcomeLabel(analysis.outcome)}</strong>
        {analysis.scopeLimited && <span> · 조회 범위 제한</span>}
      </p>
      <ul>
        {(analysis.sources || []).map((source) => (
          <li key={source.source}>
            <strong>{SOURCE_LABELS[source.source] || source.source}: {STATE_LABELS[source.status] || source.status}</strong>
            <span>{source.message}</span>
          </li>
        ))}
      </ul>
      {fallback && <p role="status">Gemini 설명 생성에 실패해 기본 설명과 질문을 제공합니다. 확인된 근거와 규칙 판정은 유지됩니다.</p>}
    </section>
  );
}
