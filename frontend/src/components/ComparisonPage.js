import AnalysisStatus from '../AnalysisStatus';
import PageHeader from './PageHeader';
import FooterActions from './FooterActions';
import InfoItem from './InfoItem';
import { CHANGE_LABELS, DOMAIN_LABELS, GAP_LABELS, STATUS_LABELS } from '../constants/labels';
import { badgeClass, gapDomain, judgementSentence } from '../utils/gap';
import { summarizeGaps } from '../utils/gap';
import { formatAmount, formatDateTime } from '../utils/formatting';

function ComparisonPage({ company, analysis, consultations, historyError, onNext }) {
  const summary = summarizeGaps(analysis.gaps);
  return (
    <div className="page-panel">
      <PageHeader eyebrow="PAGE 1" title="고객정보 비교 분석 결과" description="기존 고객정보와 최신 공시 데이터를 비교하여 확인된 변경사항을 정리했습니다." />
      <AnalysisStatus analysis={analysis} />
      <section className="judgement-summary" aria-label="판정 요약">
        <SummaryTile label="전체 후보" value={`${analysis.gaps.length}건`} />
        <SummaryTile label="기존 정보와 상충" value={`${summary.CONFLICT || 0}건`} tone="high" />
        <SummaryTile label="추가 확인 필요" value={`${summary.NEEDS_CONFIRMATION || 0}건`} tone="medium" />
        <SummaryTile label="신규/상세 정보" value={`${(summary.NEW_INFO || 0) + (summary.SPECIFIED || 0)}건`} tone="low" />
      </section>
      <ChangedDetails gaps={analysis.gaps} />
      <DisclosureOverview
        disclosures={analysis.disclosures}
        source={analysis.sources?.find((item) => item.source === 'DISCLOSURES')}
        hasGaps={analysis.gaps.length > 0}
      />
      <div className="comparison-layout">
        <section className="info-column">
          <h2>{company.companyName} 기존 정보</h2>
          <dl className="info-grid">
            <InfoItem label="최근 상담일" value={company.consultationDate || '확인된 정보 없음'} />
            <InfoItem label="투자계획" value={company.investmentPlan || '확인된 정보 없음'} />
            <InfoItem label="자금조달계획" value={company.fundingPlan || '확인된 정보 없음'} />
            <InfoItem label="해외사업계획" value={company.foreignBusinessPlan || '확인된 정보 없음'} />
            <InfoItem label="담당자 메모" value={company.rmMemo || '확인된 정보 없음'} wide />
          </dl>
        </section>
        <section className="info-column api-column">
          <h2>OpenDART 조회 정보</h2>
          <dl className="info-grid">
            <InfoItem label="기업코드" value={analysis.corpCode || '조회 정보 없음'} />
            <InfoItem label="분석 결과" value={analysis.message} wide />
          </dl>
          <FinancialSummary financials={analysis.financials} source={analysis.sources?.find((item) => item.source === 'FINANCIALS')} />
        </section>
      </div>
      <section className="history-strip">
        <h2>저장된 상담 이력</h2>
        {historyError ? <p role="alert">{historyError}</p> : consultations.length === 0 ? (
          <p>아직 저장된 상담 질문과 답변이 없습니다.</p>
        ) : (
          <ul>{consultations.slice(0, 3).map((consultation) => (
            <li key={consultation.consultationId}>
              <strong>{formatDateTime(consultation.consultedAt)}</strong>
              <span>{consultation.questions.length}개 질문</span>
            </li>
          ))}</ul>
        )}
      </section>
      <FooterActions onNext={onNext} nextLabel="변경사항과 질문 보기" />
    </div>
  );
}

function SummaryTile({ label, value, tone }) {
  return <div className={`summary-tile ${tone ? `tone-${tone}` : ''}`}><span>{label}</span><strong>{value}</strong></div>;
}

function DisclosureOverview({ disclosures = [], source, hasGaps }) {
  const visibleItems = disclosures.slice(0, 8);
  const hasMore = disclosures.length > visibleItems.length;
  const sourceFailed = source?.status === 'FAILED';

  if (hasGaps) {
    return (
      <details className="disclosure-overview disclosure-overview-compact">
        <summary>
          <span>OpenDART 조회 근거</span>
          <strong>{sourceFailed ? '조회 실패' : `${disclosures.length}건 조회`}</strong>
        </summary>
        <DisclosureOverviewBody
          disclosures={disclosures}
          visibleItems={visibleItems}
          hasMore={hasMore}
          source={source}
          sourceFailed={sourceFailed}
        />
      </details>
    );
  }

  return (
    <section className="disclosure-overview" aria-label="조회된 OpenDART 공개정보">
      <div className="section-title-row">
        <div>
          <h2>조회된 OpenDART 공개정보</h2>
          <p>최근 3년 범위에서 확인한 공시 목록입니다. 변경 후보와 별도로 조회 결과를 먼저 보여줍니다.</p>
        </div>
        <span>{sourceFailed ? '조회 실패' : `${disclosures.length}건 조회`}</span>
      </div>
      <DisclosureOverviewBody
        disclosures={disclosures}
        visibleItems={visibleItems}
        hasMore={hasMore}
        source={source}
        sourceFailed={sourceFailed}
      />
    </section>
  );
}

function DisclosureOverviewBody({ disclosures, visibleItems, hasMore, source, sourceFailed }) {
  if (visibleItems.length === 0) {
    return (
      <div className="disclosure-empty">
        <div>
          <strong>{sourceFailed ? 'OpenDART 공시 조회에 실패했습니다' : '최근 3년 범위에서 확인된 공시가 없습니다'}</strong>
          <p>
            {sourceFailed
              ? source.message || '네트워크, API 키, OpenDART 응답 상태를 확인해 주세요.'
              : '현재 조회 조건에서 표시할 공시가 없습니다. 공시가 전혀 없다고 단정하는 의미는 아닙니다.'}
          </p>
        </div>
        {!sourceFailed && (
          <dl>
            <div><dt>조회 범위</dt><dd>최근 3년</dd></div>
            <div><dt>조회 기준</dt><dd>OpenDART 최종보고서</dd></div>
          </dl>
        )}
      </div>
    );
  }

  return (
    <>
      <ul className="disclosure-list">
        {visibleItems.map((item, index) => (
          <li key={`${item.url || item.title}-${index}`}>
            <div>
              <strong>{item.title || '제목 없는 공시'}</strong>
              <span>{item.date || '공시일 확인 불가'}</span>
            </div>
            {item.url && <a href={item.url} target="_blank" rel="noreferrer">원문</a>}
          </li>
        ))}
      </ul>
      {hasMore && <p className="disclosure-more">외 {disclosures.length - visibleItems.length}건은 접어두었습니다.</p>}
    </>
  );
}

function ChangedDetails({ gaps = [] }) {
  if (!gaps.length) {
    return (
      <section className="change-detail-section">
        <div className="change-detail-empty">
          <div>
            <span>비교 결과</span>
            <strong>확인된 변경 후보가 없습니다</strong>
          </div>
          <p>현재 조회 범위에서는 기존 고객정보와 달라진 항목이 확인되지 않았습니다.</p>
        </div>
      </section>
    );
  }

  return (
    <section className="change-detail-section" aria-label="비교 분석 상세">
      <div className="section-title-row">
        <div>
          <h2>변경 포인트</h2>
          <p>기존 정보와 공개정보 사이에서 확인이 필요한 차이만 추려 표시합니다.</p>
        </div>
        <span>{gaps.length}건 확인</span>
      </div>
      <div className="change-detail-list">
        {gaps.map((gap, gapIndex) => (
          <article className={`change-detail-card severity-${(gap.severity || 'LOW').toLowerCase()}`} key={`${gap.gapType}-${gap.planId || 'unlinked'}-${gapIndex}`}>
            <div className="change-detail-main">
              <div>
                <div className="change-kicker">
                  <span>{DOMAIN_LABELS[gapDomain(gap.gapType)]}</span>
                  <strong className={`change-badge ${badgeClass(gap.changeType)}`}>
                    {gap.displayLabel || CHANGE_LABELS[gap.changeType] || '확인 필요'}
                  </strong>
                </div>
                <h3>{GAP_LABELS[gap.gapType] || gap.gapType}</h3>
                <p>{gap.reason || judgementSentence(gap)}</p>
              </div>
              <strong className="status-badge">{STATUS_LABELS[gap.assessmentStatus] || gap.assessmentStatus}</strong>
            </div>

            <div className="diff-panel">
              <DiffItem label="기존 정보" value={gap.existingInfo || '확인된 정보 없음'} />
              <DiffItem label="최신 공시/API 정보" value={gap.latestInfo || '비교 가능한 최신 정보 없음'} highlight />
            </div>

            {!!gap.changes?.length && <ChangeTable changes={gap.changes} />}
          </article>
        ))}
      </div>
    </section>
  );
}

function DiffItem({ label, value, highlight }) {
  return (
    <div className={highlight ? 'diff-item highlight' : 'diff-item'}>
      <span>{label}</span>
      <p>{value}</p>
    </div>
  );
}

function ChangeTable({ changes }) {
  return (
    <table className="change-table">
      <thead>
        <tr>
          <th>항목</th>
          <th>기존 정보</th>
          <th>최신 정보</th>
        </tr>
      </thead>
      <tbody>
        {changes.map((change) => (
          <tr key={change.field}>
            <td>{change.field === 'AMOUNT' ? '금액' : change.field === 'EXPECTED_AT' ? '일정' : change.field}</td>
            <td>{change.before}</td>
            <td>{change.after}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function FinancialSummary({ financials, source }) {
  if (!financials) return <p className="notice">{source?.message || '최근 재무정보를 확인하지 못했습니다.'}</p>;
  return <dl className="financial-grid">
    <InfoItem label="사업연도" value={financials.period} />
    <InfoItem label="재무 기준" value={financials.statementBasis === 'CFS' ? '연결' : financials.statementBasis === 'OFS' ? '별도' : '미확인'} />
    <InfoItem label="비교 기준" value="동일 결산 보고서의 전기 금액" />
    <InfoItem label="매출액" value={formatAmount(financials.revenue, financials.currency)} />
    <InfoItem label="영업이익" value={formatAmount(financials.operatingProfit, financials.currency)} />
    <InfoItem label="단기차입금" value={formatAmount(financials.shortTermDebt, financials.currency)} />
    <InfoItem label="전기 단기차입금" value={formatAmount(financials.priorPeriodShortTermDebt, financials.currency)} />
    <InfoItem label="영업활동현금흐름" value={formatAmount(financials.operatingCashFlow, financials.currency)} wide />
  </dl>;
}

export default ComparisonPage;
