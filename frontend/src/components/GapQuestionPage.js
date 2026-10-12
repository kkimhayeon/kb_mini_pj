import { outcomeLabel } from '../AnalysisStatus';
import PlanComparisons from '../PlanComparisons';
import PageHeader from './PageHeader';
import FooterActions from './FooterActions';
import { DOMAIN_LABELS, GAP_LABELS, CHANGE_LABELS, STATUS_LABELS } from '../constants/labels';
import { badgeClass, gapDomain, judgementSentence } from '../utils/gap';

function GapQuestionPage({ analysis, onPrev, onNext }) {
  return (
    <div className="page-panel gap-question-page">
      <PageHeader
        eyebrow="PAGE 2"
        title="변경사항 분석 및 상담 질문"
        description="기존 고객정보와 최신 공시를 비교하여 확인된 변경사항과 후속 상담에 필요한 질문을 정리했습니다."
      />
      <PlanComparisons comparisons={analysis.comparisons} />
      {analysis.gaps.length === 0 ? (
        <EmptyGapState analysis={analysis} />
      ) : (
        <div className="gap-list">
          {analysis.gaps.map((gap, gapIndex) => (
            <article className={`gap-card severity-${(gap.severity || 'LOW').toLowerCase()}`} key={`${gap.gapType}-${gap.planId || 'unlinked'}-${gapIndex}`}>
              <div className="gap-card-header">
                <div>
                  <span>{GAP_LABELS[gap.gapType] || gap.gapType}</span>
                  <p>{DOMAIN_LABELS[gapDomain(gap.gapType)]}</p>
                </div>
                <div className="badge-row">
                  <strong className={`change-badge ${badgeClass(gap.changeType)}`}>{gap.displayLabel || CHANGE_LABELS[gap.changeType] || '확인 필요'}</strong>
                  <strong className="status-badge">{STATUS_LABELS[gap.assessmentStatus] || gap.assessmentStatus}</strong>
                </div>
              </div>

              <div className="before-after">
                <div>
                  <h3>기존 정보</h3>
                  <p>{gap.existingInfo || '확인된 정보 없음'}</p>
                </div>
                <div>
                  <h3>최신 공시 정보</h3>
                  <p>{gap.latestInfo || '비교 가능한 최신 정보 없음'}</p>
                </div>
              </div>

              <div className="reason-box">
                <h3>변경 판단 근거</h3>
                <p>{gap.reason || judgementSentence(gap)}</p>
                <p className="hint">{explanationLabel(gap.explanationSource)}</p>
              </div>

              <div className="question-box">
                <h3>추천 상담 질문</h3>
                {gap.questions?.length ? (
                  <ol>{gap.questions.map((question) => <li key={question}>{question}</li>)}</ol>
                ) : (
                  <p>현재 분석 결과에서 생성된 상담 질문이 없습니다.</p>
                )}
              </div>
              <EvidenceList evidence={gap.evidence} />
            </article>
          ))}
        </div>
      )}
      <FooterActions onPrev={onPrev} onNext={onNext} nextLabel="답변 입력하기" />
    </div>
  );
}

function EmptyGapState({ analysis }) {
  const failed = analysis.outcome === 'NO_DATA' || analysis.sources?.some((source) => source.status === 'FAILED');
  const inconclusive = analysis.outcome === 'INCONCLUSIVE';
  const title = failed
    ? '변경사항 분석을 완료하지 못했습니다.'
    : inconclusive
      ? '비교 가능한 정보가 부족합니다.'
      : '확인된 변경 근거가 없습니다.';
  const description = failed
    ? '일부 데이터를 조회하거나 분석하는 과정에서 문제가 발생했습니다. 위 조회 상태를 확인해 주세요.'
    : inconclusive
      ? '기존 고객정보 또는 최신 공시정보가 부족하여 변경사항을 충분히 분석하지 못했습니다.'
      : '현재 조회 범위와 비교 기준에서는 변경 근거를 발견하지 못했습니다. 실제 변경이 없다고 확정하는 결과는 아닙니다.';

  return (
    <div className="empty-box">
      <h2>{title}</h2>
      <p>{analysis.message || outcomeLabel(analysis.outcome) || description}</p>
      <p>{description}</p>
    </div>
  );
}

function explanationLabel(source) {
  if (source === 'Gemini') return 'AI 보조 설명과 질문 생성 결과입니다. 실제 상담에서 추가 확인이 필요합니다.';
  if (source === 'Rule fallback (Gemini failed)') return 'AI 설명 생성 실패로 규칙 기반 기본 설명과 질문을 사용했습니다.';
  return '규칙 기반 기본 설명과 질문을 사용했습니다.';
}

function EvidenceList({ evidence }) {
  if (!evidence?.length) return null;
  return (
    <div className="evidence-box">
      <h3>근거 자료</h3>
      <ul>
        {evidence.map((item) => (
          <li key={`${item.date}-${item.title}-${item.url}`}>
            <span>{item.source} · {item.date || '일자 확인 필요'}</span>
            {item.url ? <a href={item.url} target="_blank" rel="noreferrer">{item.title}</a> : <strong>{item.title}</strong>}
          </li>
        ))}
      </ul>
    </div>
  );
}

export default GapQuestionPage;
