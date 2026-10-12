import { useEffect, useMemo, useRef, useState } from 'react';
import {
  createConsultation,
  getCompanies,
  getCompanyAnalysis,
  getConsultations,
  discoverCompany,
} from './api';
import './App.css';
import AnswerEditor from './AnswerEditor';
import AnalysisStatus, { outcomeLabel } from './AnalysisStatus';
import PlanComparisons from './PlanComparisons';
import { buildConsultationQuestions } from './consultationDraft';
import ConsultationMemo from './components/ConsultationMemo';
import StepNavigation from './components/StepNavigation';
import PageHeader from './components/PageHeader';
import FooterActions from './components/FooterActions';
import InfoItem from './components/InfoItem';
import CompanyInputPageView from './components/CompanyInputPage';
import ComparisonPageView from './components/ComparisonPage';
import GapQuestionPageView from './components/GapQuestionPage';
import AnswerPageView from './components/AnswerPage';
import { findPlanId, normalize, resolveCompany } from './utils/company';
import { badgeClass, gapDomain, judgementSentence, summarizeGaps } from './utils/gap';
import { formatAmount, formatDateTime } from './utils/formatting';
import {
  CHANGE_LABELS,
  COMPANY_INPUT_LABELS,
  DOMAIN_LABELS,
  GAP_LABELS,
  STATUS_LABELS,
  STEP_LABELS,
} from './constants/labels';

const LEGACY_DOMAIN_LABELS = {
  INVESTMENT: '투자',
  FUNDING: '자금조달',
  FX: '해외사업',
};

const LEGACY_GAP_LABELS = {
  INVESTMENT_PLAN_GAP: '투자계획 변경 후보',
  FUNDING_PLAN_GAP: '자금조달 변경 후보',
  FX_BUSINESS_GAP: '해외사업 변경 후보',
};

const LEGACY_CHANGE_LABELS = {
  CONFLICT: '기존 정보와 상충',
  NEEDS_CONFIRMATION: '추가 확인 필요',
  SPECIFIED: '기존 계획과 다른 공개정보',
  NEW_INFO: '신규 공개정보 발견',
};

const LEGACY_STATUS_LABELS = {
  CONFIRMED: '근거 확인됨',
  PENDING: '상담 확인 필요',
  NO_CHANGE_EVIDENCE: '변경 근거 없음',
  RETRIEVAL_FAILED: '조회 실패',
};

const LEGACY_STEP_LABELS = ['기업 입력', '정보 비교', 'Gap 및 질문', '답변 저장'];

function App() {
  const [companies, setCompanies] = useState([]);
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [analysis, setAnalysis] = useState(null);
  const [consultations, setConsultations] = useState([]);
  const [historyError, setHistoryError] = useState('');
  const [step, setStep] = useState(0);
  const [loadingCompanies, setLoadingCompanies] = useState(true);
  const [loadingAnalysis, setLoadingAnalysis] = useState(false);
  const [savingAnswers, setSavingAnswers] = useState(false);
  const [answerDrafts, setAnswerDrafts] = useState({});
  const saveInFlight = useRef(false);
  const [saveMessage, setSaveMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    getCompanies()
      .then((items) => setCompanies(items))
      .catch((loadError) => setError(loadError.message))
      .finally(() => setLoadingCompanies(false));
  }, []);

  const selectedCompany = useMemo(
    () => analysis?.company || companies.find((company) => company.companyId === selectedId) || null,
    [companies, selectedId, analysis]
  );

  const generatedQuestions = useMemo(() => {
    if (!analysis?.gaps) return [];
    return analysis.gaps.flatMap((gap, gapIndex) => (gap.questions || []).map((question, index) => ({
      key: `${gap.gapType}-${gap.planId || 'unlinked'}-${gapIndex}-${index}`,
      gapType: gap.gapType,
      changeType: gap.changeType,
      displayLabel: gap.displayLabel,
      domain: gapDomain(gap.gapType),
      planId: gap.planId ?? findPlanId(companies, selectedId, gapDomain(gap.gapType)),
      questionText: question,
      evidence: gap.evidence || [],
    })));
  }, [analysis, companies, selectedId]);

  async function analyzeCompany(event) {
    event.preventDefault();
    const company = resolveCompany(companies, query);
    if (!company) {
      if (query.trim()) {
        setSelectedId('');
        setAnalysis(null);
        setConsultations([]);
        setHistoryError('DB 미등록 기업은 저장된 상담 이력 없이 공개정보만 조회합니다.');
        setAnswerDrafts({});
        setSaveMessage('');
        setError('');
        setLoadingAnalysis(true);
        try {
          const result = await discoverCompany(query.trim());
          setAnalysis(result);
          setStep(1);
        } catch (analysisError) {
          setError(analysisError.message);
          setStep(0);
        } finally {
          setLoadingAnalysis(false);
        }
        return;
      }
      setError('DB에 있는 기업명을 입력하거나 목록에서 선택해주세요.');
      return;
    }

    setSelectedId(company.companyId);
    setAnalysis(null);
    setConsultations([]);
    setHistoryError('');
    setAnswerDrafts({});
    setSaveMessage('');
    setError('');
    setLoadingAnalysis(true);

    try {
      const result = await getCompanyAnalysis(company.companyId);
      setAnalysis(result);
      setStep(1);
      try {
        setConsultations(await getConsultations(company.companyId));
      } catch {
        setHistoryError('상담 이력을 불러오지 못했습니다. 분석 결과는 아래에서 확인할 수 있습니다.');
      }
    } catch (analysisError) {
      setError(analysisError.message);
      setStep(0);
    } finally {
      setLoadingAnalysis(false);
    }
  }

  async function saveAnswers() {
    if (!selectedId || generatedQuestions.length === 0 || saveInFlight.current) return;
    if (!generatedQuestions.some((item) => answerDrafts[item.key]?.answerText?.trim())) {
      setError('저장할 답변을 1개 이상 입력해주세요.');
      return;
    }

    saveInFlight.current = true;
    setSavingAnswers(true);
    setError('');
    setSaveMessage('');

    try {
      const now = new Date().toISOString();
      const questions = buildConsultationQuestions(generatedQuestions, answerDrafts, now);
      const consultation = await createConsultation(selectedId, {
        consultedAt: now,
        channel: 'OTHER',
        consultantName: 'RM',
        memo: 'Gap 분석 결과 기반 후속 상담',
        questions,
      });

      setConsultations((items) => [consultation, ...items.filter(
        (item) => item.consultationId !== consultation.consultationId
      )]);
      setAnswerDrafts({});
      setSaveMessage('상담과 답변을 모두 저장했습니다. 확인된 계획 항목만 다음 분석에 반영됩니다.');
    } catch (saveError) {
      setError(saveError.message);
    } finally {
      saveInFlight.current = false;
      setSavingAnswers(false);
    }
  }

  return (
    <main className="app-shell">
      <section className="workspace" aria-label="Corporate Client Gap Agent">
        <StepNavigation step={step} setStep={setStep} analysis={analysis} busy={savingAnswers} labels={STEP_LABELS} />

        {step === 0 && (
          <CompanyInputPageView
            query={query}
            setQuery={setQuery}
            companies={companies}
            loadingCompanies={loadingCompanies}
            loadingAnalysis={loadingAnalysis}
            onSubmit={analyzeCompany}
            labels={COMPANY_INPUT_LABELS}
          />
        )}

        {step === 1 && analysis && selectedCompany && (
          <ComparisonPageView
            company={selectedCompany}
            analysis={analysis}
            consultations={consultations}
            historyError={historyError}
            onNext={() => setStep(2)}
          />
        )}

        {step === 2 && analysis && (
          <GapQuestionPageView
            analysis={analysis}
            onPrev={() => setStep(1)}
            onNext={() => setStep(3)}
          />
        )}

        {step === 3 && analysis && (
          <AnswerPageView
            questions={generatedQuestions}
            answerDrafts={answerDrafts}
            setAnswerDrafts={setAnswerDrafts}
            consultations={consultations}
            saveMessage={saveMessage}
            savingAnswers={savingAnswers}
            canSave={Boolean(selectedId)}
            onPrev={() => setStep(2)}
            onSave={saveAnswers}
          />
        )}

        {error && <p className="error-message" role="alert">{error}</p>}
      </section>
    </main>
  );
}

function LegacyStepNavigation({ step, setStep, analysis, busy }) {
  return (
    <nav className="step-nav" aria-label="분석 단계">
      {STEP_LABELS.map((label, index) => (
        <button
          key={label}
          type="button"
          className={index === step ? 'active' : ''}
          disabled={busy || (index > 0 && !analysis)}
          onClick={() => setStep(index)}
        >
          <span>{index + 1}</span>
          {label}
        </button>
      ))}
    </nav>
  );
}

function LegacyCompanyInputPage({ query, setQuery, companies, loadingCompanies, loadingAnalysis, onSubmit }) {
  return (
    <div className="single-card input-page">
      <p className="eyebrow">Corporate Client Gap Agent</p>
      <h1>{COMPANY_INPUT_LABELS.title}</h1>
      <form className="company-search" onSubmit={onSubmit}>
        <label htmlFor="company-query">{COMPANY_INPUT_LABELS.field}</label>
        <div className="search-row">
          <input
            id="company-query"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            list="company-options"
            placeholder={COMPANY_INPUT_LABELS.placeholder}
            autoComplete="off"
          />
          <button type="submit" disabled={loadingCompanies || loadingAnalysis || !query.trim()}>
            {loadingAnalysis ? COMPANY_INPUT_LABELS.loading : COMPANY_INPUT_LABELS.submit}
          </button>
        </div>
        <datalist id="company-options">
          {companies.map((company) => (
            <option key={company.companyId} value={company.companyName} />
          ))}
        </datalist>
      </form>
      <p className="hint">
        {COMPANY_INPUT_LABELS.hint}
      </p>
    </div>
  );
}

function LegacyComparisonPage({ company, analysis, consultations, historyError, onNext }) {
  const summary = summarizeGaps(analysis.gaps);

  return (
    <div className="page-panel">
      <PageHeader
        eyebrow="PAGE 1"
        title="DB 정보와 API 정보를 한눈에 비교"
        description="RM이 보유한 기존 고객정보와 OpenDART 조회 결과를 같은 화면에서 확인합니다."
      />

      <AnalysisStatus analysis={analysis} />

      <section className="judgement-summary" aria-label="판정 요약">
        <SummaryTile label="전체 후보" value={`${analysis.gaps.length}건`} />
        <SummaryTile label="기존 정보와 상충" value={`${summary.CONFLICT || 0}건`} tone="high" />
        <SummaryTile label="추가 확인 필요" value={`${summary.NEEDS_CONFIRMATION || 0}건`} tone="medium" />
        <SummaryTile label="신규/상세 정보" value={`${(summary.NEW_INFO || 0) + (summary.SPECIFIED || 0)}건`} tone="low" />
      </section>

      <div className="comparison-layout">
        <section className="info-column">
          <h2>{company.companyName} DB 정보</h2>
          <dl className="info-grid">
            <InfoItem label="최근 상담일" value={company.consultationDate || '확인된 정보 없음'} />
            <InfoItem label="투자계획" value={company.investmentPlan || '확인된 정보 없음'} />
            <InfoItem label="자금조달계획" value={company.fundingPlan || '확인된 정보 없음'} />
            <InfoItem label="해외사업계획" value={company.foreignBusinessPlan || '확인된 정보 없음'} />
            <InfoItem label="RM 메모" value={company.rmMemo || '확인된 정보 없음'} wide />
          </dl>
        </section>

        <section className="info-column api-column">
          <h2>OpenDART API 조회 정보</h2>
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
          <ul>
            {consultations.slice(0, 3).map((consultation) => (
              <li key={consultation.consultationId}>
                <strong>{formatDateTime(consultation.consultedAt)}</strong>
                <span>{consultation.questions.length}개 질문</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <FooterActions onNext={onNext} nextLabel="변경사항과 질문 보기" />
    </div>
  );
}

function LegacyGapQuestionPage({ analysis, onPrev, onNext }) {
  return (
    <div className="page-panel">
      <PageHeader
        eyebrow="PAGE 2"
        title="무엇이 달라졌고 무엇을 물어봐야 하는지"
        description="판정 유형별로 기존 RM 정보, 최신 공개정보, 판단 이유, 후속 질문을 묶어서 보여줍니다."
      />

      <AnalysisStatus analysis={analysis} />
      <PlanComparisons comparisons={analysis.comparisons} />
      {analysis.gaps.length === 0 ? (
        <div className="empty-box">
          <h2>{outcomeLabel(analysis.outcome)}</h2>
          <p>{analysis.message}</p>
        </div>
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

              <div className="judgement-line">
                <strong>판단</strong>
                <span>{judgementSentence(gap)}</span>
              </div>

              <div className="before-after">
                <div>
                  <h3>DB/RM 기존 정보</h3>
                  <p>{gap.existingInfo || '확인된 정보 없음'}</p>
                </div>
                <div>
                  <h3>API 기반 최신 정보</h3>
                  <p>{gap.latestInfo}</p>
                </div>
              </div>
              <div className="reason-box">
                <h3>판정 근거</h3>
                <p>{gap.reason}</p>
                <p className="hint">{gap.explanationSource === 'Gemini' ? 'Gemini 보조 설명·질문'
                  : gap.explanationSource === 'Rule fallback (Gemini failed)'
                    ? 'Gemini 응답 실패 · 규칙 기반 기본 설명·질문 사용'
                    : '규칙 기반 기본 설명·질문'}</p>
              </div>
              <div className="question-box">
                <h3>다음 상담 질문</h3>
                <ol>
                  {gap.questions.map((question) => <li key={question}>{question}</li>)}
                </ol>
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

function LegacyAnswerPage({ questions, answerDrafts, setAnswerDrafts, consultations, saveMessage, savingAnswers, canSave, onPrev, onSave }) {
  return (
    <div className="page-panel">
      <PageHeader
        eyebrow="PAGE 3"
        title="질문 답변 저장"
        description="답변 원문을 기록하고, 다음 분석에 반영할 계획 항목을 입력해 확인해주세요. 입력하지 않은 항목은 기존 정보를 유지합니다."
      />

      {questions.length === 0 ? (
        <div className="empty-box">
          <h2>저장할 질문이 없습니다</h2>
          <p>이번 분석에서 생성된 질문이 없어 답변 저장 단계가 비어 있습니다.</p>
        </div>
      ) : (
        <div className="answer-list">
          {questions.map((question, index) => (
            <AnswerEditor
              key={question.key}
              question={question}
              number={index + 1}
              domainLabel={DOMAIN_LABELS[question.domain]}
              draft={answerDrafts[question.key]}
              disabled={savingAnswers}
              onChange={(draft) => setAnswerDrafts((drafts) => ({
                ...drafts,
                [question.key]: draft,
              }))}
            />
          ))}
        </div>
      )}

      {saveMessage && <p className="success-message">{saveMessage}</p>}

      {!canSave && <p className="notice">DB 미등록 기업의 공개정보 결과입니다. 상담 답변을 저장하려면 기업을 먼저 등록해주세요.</p>}

      <ConsultationMemo />

      {consultations.length > 0 && (
        <section className="saved-history">
          <h2>최근 DB 저장 이력</h2>
          <ul>
            {consultations.slice(0, 3).map((consultation) => (
              <li key={consultation.consultationId}>
                <strong>{formatDateTime(consultation.consultedAt)}</strong>
                <span>{consultation.memo || '상담 기록'}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <FooterActions
        onPrev={onPrev}
        onSave={onSave}
        saveLabel={savingAnswers ? '저장 중' : 'DB에 답변 저장'}
        prevDisabled={savingAnswers}
        saveDisabled={!canSave || savingAnswers || !questions.some((question) => answerDrafts[question.key]?.answerText?.trim())}
      />
    </div>
  );
}

function SummaryTile({ label, value, tone }) {
  return (
    <div className={`summary-tile ${tone ? `tone-${tone}` : ''}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function LegacyPageHeader({ eyebrow, title, description }) {
  return (
    <header className="page-heading">
      <p className="eyebrow">{eyebrow}</p>
      <h1>{title}</h1>
      <p>{description}</p>
    </header>
  );
}

function LegacyFooterActions({ onPrev, onNext, onSave, nextLabel, saveLabel, saveDisabled, prevDisabled }) {
  return (
    <div className="footer-actions">
      {onPrev && <button type="button" className="secondary-button" onClick={onPrev} disabled={prevDisabled}>이전</button>}
      <div />
      {onNext && <button type="button" onClick={onNext}>{nextLabel}</button>}
      {onSave && <button type="button" onClick={onSave} disabled={saveDisabled}>{saveLabel}</button>}
    </div>
  );
}

function LegacyInfoItem({ label, value, wide }) {
  return (
    <div className={wide ? 'wide' : ''}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function FinancialSummary({ financials, source }) {
  if (!financials) {
    return <p className="notice">{source?.message || '최근 재무정보를 확인하지 못했습니다.'}</p>;
  }

  return (
    <dl className="financial-grid">
      <InfoItem label="사업연도" value={financials.period} />
      <InfoItem label="재무 기준" value={financials.statementBasis === 'CFS' ? '연결' : financials.statementBasis === 'OFS' ? '별도' : '미확인'} />
      <InfoItem label="비교 기준" value="동일 연간 보고서의 당기·전기 금액" />
      <InfoItem label="매출액" value={formatAmount(financials.revenue, financials.currency)} />
      <InfoItem label="영업이익" value={formatAmount(financials.operatingProfit, financials.currency)} />
      <InfoItem label="단기차입금" value={formatAmount(financials.shortTermDebt, financials.currency)} />
      <InfoItem label="전기 단기차입금" value={formatAmount(financials.priorPeriodShortTermDebt, financials.currency)} />
      <InfoItem label="영업현금흐름" value={formatAmount(financials.operatingCashFlow, financials.currency)} wide />
    </dl>
  );
}

function EvidenceList({ evidence }) {
  return (
    <div className="evidence-box">
      <h3>공시 근거</h3>
      {evidence?.length ? (
        <ul>
          {evidence.map((item) => (
            <li key={`${item.date}-${item.title}-${item.url}`}>
              <span>{item.source} · {item.date || '일자 확인 필요'}</span>
              {item.url ? <a href={item.url} target="_blank" rel="noreferrer">{item.title}</a> : <strong>{item.title}</strong>}
            </li>
          ))}
        </ul>
      ) : (
        <p>저장된 공시 근거가 없습니다.</p>
      )}
    </div>
  );
}

function legacySummarizeGaps(gaps = []) {
  return gaps.reduce((acc, gap) => {
    const key = gap.changeType || 'UNKNOWN';
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});
}

function legacyJudgementSentence(gap) {
  if (gap.changeType === 'CONFLICT') {
    return '기존 RM 정보와 공개정보가 서로 다를 가능성이 커서 우선 확인해야 합니다.';
  }
  if (gap.changeType === 'NEEDS_CONFIRMATION') {
    return '기존 정보와 완전히 충돌한다고 보기는 어렵지만 상담에서 진행 상태를 확인해야 합니다.';
  }
  if (gap.changeType === 'SPECIFIED') {
    return '기존 계획과 다른 범위의 공개정보가 발견되어 동일 건인지 신규 건인지 확인해야 합니다.';
  }
  return 'RM 정보에 없던 공개정보가 발견되어 후속 상담에서 확인할 필요가 있습니다.';
}

function legacyBadgeClass(changeType) {
  if (changeType === 'CONFLICT') return 'badge-high';
  if (changeType === 'NEEDS_CONFIRMATION') return 'badge-medium';
  if (changeType === 'SPECIFIED') return 'badge-medium';
  return 'badge-low';
}

function legacyResolveCompany(companies, query) {
  const normalized = normalize(query);
  return companies.find((company) => normalize(company.companyName) === normalized)
    || companies.find((company) => normalize(company.companyName).includes(normalized))
    || companies.find((company) => company.companyId === query.trim());
}

function legacyFindPlanId(companies, selectedId, domain) {
  const selected = companies.find((company) => company.companyId === selectedId);
  const matching = selected?.plans?.filter((plan) => plan.domain === domain) || [];
  return matching.length === 1 ? matching[0].planId : null;
}

function legacyGapDomain(gapType) {
  if (gapType === 'FUNDING_PLAN_GAP') return 'FUNDING';
  if (gapType === 'FX_BUSINESS_GAP') return 'FX';
  return 'INVESTMENT';
}

function legacyNormalize(value) {
  return String(value || '').replace(/\s+/g, '').toLowerCase();
}

function legacyFormatDateTime(value) {
  if (!value) return '일시 미확인';
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}

function legacyFormatAmount(amount, currency) {
  if (amount === null || amount === undefined) return '확인된 정보 없음';
  return `${new Intl.NumberFormat('ko-KR').format(amount)} ${currency === 'KRW' ? '원' : currency || '통화 미확인'}`;
}

export default App;
