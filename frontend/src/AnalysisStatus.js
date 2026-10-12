const SOURCE_LABELS = {
  COMPANY: '기업정보',
  FINANCIALS: '재무정보',
  DISCLOSURES: '공시정보',
  DISCLOSURE_DETAILS: '공시 상세',
};

const STATE_LABELS = {
  SUCCESS: '조회 성공',
  NO_DATA: '자료 없음',
  NOT_REQUESTED: '조회 제외',
  FAILED: '조회 실패',
  PARTIAL: '일부 조회',
};

export function outcomeLabel(outcome) {
  return {
    GAPS_FOUND: '변경 후보 확인',
    NO_CHANGE_EVIDENCE: '변경 근거 없음',
    INCONCLUSIVE: '자료 부족으로 판단 제한',
    NO_DATA: '분석 가능한 공개정보 없음',
  }[outcome] || '자료 확인 필요';
}

function statusTone(status) {
  if (status === 'SUCCESS') return 'success';
  if (status === 'FAILED') return 'failed';
  if (status === 'PARTIAL' || status === 'NO_DATA') return 'partial';
  return 'neutral';
}

export default function AnalysisStatus({ analysis }) {
  if (!analysis.outcome && !analysis.sources?.length) return null;
  const fallback = analysis.gaps?.some((gap) => gap.explanationSource === 'Rule fallback (Gemini failed)');

  return (
    <section className="analysis-status" aria-label="분석 및 조회 상태">
      <p>
        <strong>{outcomeLabel(analysis.outcome)}</strong>
        {analysis.scopeLimited && <span> · 조회 범위 제한</span>}
      </p>
      {!!analysis.sources?.length && (
        <ul>
          {analysis.sources.map((source) => (
            <li className={`status-${statusTone(source.status)}`} key={source.source}>
              <strong>{SOURCE_LABELS[source.source] || source.source}: {STATE_LABELS[source.status] || source.status}</strong>
              {source.message && <span>{source.message}</span>}
            </li>
          ))}
        </ul>
      )}
      {fallback && <p role="status">AI 설명 생성에 실패한 항목은 규칙 기반 기본 설명과 질문을 사용했습니다.</p>}
    </section>
  );
}
