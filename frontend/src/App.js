import { useEffect, useMemo, useState } from 'react';
import {
  answerQuestion,
  createConsultation,
  getCompanies,
  getCompanyAnalysis,
  getConsultations,
} from './api';
import './App.css';

const DOMAIN_LABELS = {
  INVESTMENT: '투자',
  FUNDING: '자금조달',
  FX: '해외사업',
};

const GAP_LABELS = {
  INVESTMENT_PLAN_GAP: '투자계획 변경 후보',
  FUNDING_PLAN_GAP: '자금조달 변경 후보',
  FX_BUSINESS_GAP: '해외사업 변경 후보',
};

const STEP_LABELS = ['기업 입력', '정보 비교', 'Gap 및 질문', '답변 저장'];

function App() {
  const [companies, setCompanies] = useState([]);
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [analysis, setAnalysis] = useState(null);
  const [consultations, setConsultations] = useState([]);
  const [step, setStep] = useState(0);
  const [loadingCompanies, setLoadingCompanies] = useState(true);
  const [loadingAnalysis, setLoadingAnalysis] = useState(false);
  const [savingAnswers, setSavingAnswers] = useState(false);
  const [answerDrafts, setAnswerDrafts] = useState({});
  const [saveMessage, setSaveMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    getCompanies()
      .then((items) => setCompanies(items))
      .catch((loadError) => setError(loadError.message))
      .finally(() => setLoadingCompanies(false));
  }, []);

  const selectedCompany = useMemo(
    () => companies.find((company) => company.companyId === selectedId) || analysis?.company || null,
    [companies, selectedId, analysis]
  );

  const generatedQuestions = useMemo(() => {
    if (!analysis?.gaps) return [];
    return analysis.gaps.flatMap((gap) => gap.questions.map((question, index) => ({
      key: `${gap.gapType}-${index}`,
      gapType: gap.gapType,
      domain: gapDomain(gap.gapType),
      planId: findPlanId(companies, selectedId, gapDomain(gap.gapType)),
      questionText: question,
      evidence: gap.evidence || [],
    })));
  }, [analysis, companies, selectedId]);

  async function analyzeCompany(event) {
    event.preventDefault();
    const company = resolveCompany(companies, query);
    if (!company) {
      setError('DB에 있는 기업명을 입력하거나 목록에서 선택해주세요.');
      return;
    }

    setSelectedId(company.companyId);
    setAnalysis(null);
    setConsultations([]);
    setAnswerDrafts({});
    setSaveMessage('');
    setError('');
    setLoadingAnalysis(true);

    try {
      const result = await getCompanyAnalysis(company.companyId);
      setAnalysis(result);
      setConsultations(await getConsultations(company.companyId));
      setStep(1);
    } catch (analysisError) {
      setError(analysisError.message);
      setStep(0);
    } finally {
      setLoadingAnalysis(false);
    }
  }

  async function saveAnswers() {
    if (!selectedId || generatedQuestions.length === 0) return;

    const answeredQuestions = generatedQuestions.filter((item) => (answerDrafts[item.key] || '').trim());
    if (answeredQuestions.length === 0) {
      setError('저장할 답변을 1개 이상 입력해주세요.');
      return;
    }

    setSavingAnswers(true);
    setError('');
    setSaveMessage('');

    try {
      const consultation = await createConsultation(selectedId, {
        consultedAt: new Date().toISOString(),
        channel: 'OTHER',
        consultantName: 'RM',
        memo: 'Gap 분석 결과 기반 후속 상담',
        questions: generatedQuestions.map((item) => ({
          planId: item.planId,
          questionSource: 'AI_GENERATED',
          questionText: item.questionText,
        })),
      });

      for (const item of answeredQuestions) {
        const qa = consultation.questions.find((question) => question.questionText === item.questionText);
        if (!qa) continue;
        const answerText = answerDrafts[item.key].trim();
        await answerQuestion(qa.qaId, {
          answerStatus: 'ANSWERED',
          answerText,
          answeredAt: new Date().toISOString(),
          answeredBy: '고객',
          planFact: {
            planId: item.planId,
            domain: item.domain,
            scope: answerText,
            planStatus: inferPlanStatus(answerText),
            extractionMethod: 'RM_ENTERED',
            verificationStatus: 'RM_CONFIRMED',
            effectiveFrom: new Date().toISOString(),
            note: 'Gap 분석 후속 상담 답변',
          },
        });
      }

      setConsultations(await getConsultations(selectedId));
      setAnswerDrafts({});
      setSaveMessage('상담 질문과 답변을 DB에 저장했습니다. 다음 분석에서 이 답변이 RM 확인 정보로 재사용됩니다.');
    } catch (saveError) {
      setError(saveError.message);
    } finally {
      setSavingAnswers(false);
    }
  }

  return (
    <main className="app-shell">
      <section className="workspace" aria-label="Corporate Client Gap Agent">
        <StepNavigation step={step} setStep={setStep} analysis={analysis} />

        {step === 0 && (
          <CompanyInputPage
            query={query}
            setQuery={setQuery}
            companies={companies}
            loadingCompanies={loadingCompanies}
            loadingAnalysis={loadingAnalysis}
            onSubmit={analyzeCompany}
          />
        )}

        {step === 1 && analysis && selectedCompany && (
          <ComparisonPage
            company={selectedCompany}
            analysis={analysis}
            consultations={consultations}
            onNext={() => setStep(2)}
          />
        )}

        {step === 2 && analysis && (
          <GapQuestionPage
            analysis={analysis}
            onPrev={() => setStep(1)}
            onNext={() => setStep(3)}
          />
        )}

        {step === 3 && analysis && (
          <AnswerPage
            questions={generatedQuestions}
            answerDrafts={answerDrafts}
            setAnswerDrafts={setAnswerDrafts}
            consultations={consultations}
            saveMessage={saveMessage}
            savingAnswers={savingAnswers}
            onPrev={() => setStep(2)}
            onSave={saveAnswers}
          />
        )}

        {error && <p className="error-message" role="alert">{error}</p>}
      </section>
    </main>
  );
}

function StepNavigation({ step, setStep, analysis }) {
  return (
    <nav className="step-nav" aria-label="분석 단계">
      {STEP_LABELS.map((label, index) => (
        <button
          key={label}
          type="button"
          className={index === step ? 'active' : ''}
          disabled={index > 0 && !analysis}
          onClick={() => setStep(index)}
        >
          <span>{index + 1}</span>
          {label}
        </button>
      ))}
    </nav>
  );
}

function CompanyInputPage({ query, setQuery, companies, loadingCompanies, loadingAnalysis, onSubmit }) {
  return (
    <div className="single-card input-page">
      <p className="eyebrow">Corporate Client Gap Agent</p>
      <h1>분석할 기업을 입력해주세요</h1>
      <form className="company-search" onSubmit={onSubmit}>
        <label htmlFor="company-query">기업명</label>
        <div className="search-row">
          <input
            id="company-query"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            list="company-options"
            placeholder="예: 고려아연, 롯데케미칼"
            autoComplete="off"
          />
          <button type="submit" disabled={loadingCompanies || loadingAnalysis || !query.trim()}>
            {loadingAnalysis ? '분석 중' : '분석 시작'}
          </button>
        </div>
        <datalist id="company-options">
          {companies.map((company) => (
            <option key={company.companyId} value={company.companyName} />
          ))}
        </datalist>
      </form>
      <p className="hint">
        입력한 기업을 DB에서 찾은 뒤 RM 기존 정보와 OpenDART API 정보를 함께 조회합니다.
      </p>
    </div>
  );
}

function ComparisonPage({ company, analysis, consultations, onNext }) {
  return (
    <div className="page-panel">
      <PageHeader
        eyebrow="PAGE 1"
        title="DB 정보와 API 정보를 한눈에 비교"
        description="RM이 보유한 기존 고객정보, OpenDART 기업코드, 재무정보, 최근 상담 이력을 같은 화면에서 확인합니다."
      />

      <div className="comparison-layout">
        <section className="info-column">
          <h2>{company.companyName} DB 정보</h2>
          <dl className="info-grid">
            <InfoItem label="최근 상담일" value={company.consultationDate} />
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
          <FinancialSummary financials={analysis.financials} />
        </section>
      </div>

      <section className="history-strip">
        <h2>저장된 상담 이력</h2>
        {consultations.length === 0 ? (
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

function GapQuestionPage({ analysis, onPrev, onNext }) {
  return (
    <div className="page-panel">
      <PageHeader
        eyebrow="PAGE 2"
        title="변경 후보와 다음 상담 질문"
        description="공개정보와 RM 기존 정보가 어긋날 가능성이 있는 항목을 확인하고, 다음 상담에서 물어볼 질문을 검토합니다."
      />

      {analysis.gaps.length === 0 ? (
        <div className="empty-box">
          <h2>확인된 Gap 없음</h2>
          <p>현재 조회 가능한 공개정보 기준으로 규칙에 걸린 변경 후보가 없습니다.</p>
        </div>
      ) : (
        <div className="gap-list">
          {analysis.gaps.map((gap) => (
            <article className="gap-card" key={gap.gapType}>
              <div className="gap-card-header">
                <span>{GAP_LABELS[gap.gapType] || gap.gapType}</span>
                <strong>{gap.explanationSource}</strong>
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
                <h3>변경 후보 판단</h3>
                <p>{gap.reason}</p>
              </div>
              <div className="question-box">
                <h3>상담 질문</h3>
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

function AnswerPage({ questions, answerDrafts, setAnswerDrafts, consultations, saveMessage, savingAnswers, onPrev, onSave }) {
  return (
    <div className="page-panel">
      <PageHeader
        eyebrow="PAGE 3"
        title="질문 답변 저장"
        description="상담 질문에 대한 고객 답변을 입력하면 상담 이력과 구조화된 계획 근거로 DB에 저장됩니다."
      />

      {questions.length === 0 ? (
        <div className="empty-box">
          <h2>저장할 질문이 없습니다</h2>
          <p>이번 분석에서 생성된 질문이 없어서 답변 저장 단계가 비어 있습니다.</p>
        </div>
      ) : (
        <div className="answer-list">
          {questions.map((question, index) => (
            <article className="answer-item" key={question.key}>
              <div className="answer-question">
                <span>{DOMAIN_LABELS[question.domain]} 질문 {index + 1}</span>
                <p>{question.questionText}</p>
              </div>
              <textarea
                value={answerDrafts[question.key] || ''}
                onChange={(event) => setAnswerDrafts((drafts) => ({
                  ...drafts,
                  [question.key]: event.target.value,
                }))}
                placeholder="고객 답변을 입력해주세요"
                rows="4"
              />
            </article>
          ))}
        </div>
      )}

      {saveMessage && <p className="success-message">{saveMessage}</p>}

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
        saveDisabled={savingAnswers || questions.length === 0}
      />
    </div>
  );
}

function PageHeader({ eyebrow, title, description }) {
  return (
    <header className="page-heading">
      <p className="eyebrow">{eyebrow}</p>
      <h1>{title}</h1>
      <p>{description}</p>
    </header>
  );
}

function FooterActions({ onPrev, onNext, onSave, nextLabel, saveLabel, saveDisabled }) {
  return (
    <div className="footer-actions">
      {onPrev && <button type="button" className="secondary-button" onClick={onPrev}>이전</button>}
      <div />
      {onNext && <button type="button" onClick={onNext}>{nextLabel}</button>}
      {onSave && <button type="button" onClick={onSave} disabled={saveDisabled}>{saveLabel}</button>}
    </div>
  );
}

function InfoItem({ label, value, wide }) {
  return (
    <div className={wide ? 'wide' : ''}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function FinancialSummary({ financials }) {
  if (!financials) {
    return <p className="notice">최근 재무정보를 확인하지 못했습니다.</p>;
  }

  return (
    <dl className="financial-grid">
      <InfoItem label="사업연도" value={financials.period} />
      <InfoItem label="매출액" value={formatAmount(financials.revenue)} />
      <InfoItem label="영업이익" value={formatAmount(financials.operatingProfit)} />
      <InfoItem label="단기차입금" value={formatAmount(financials.shortTermDebt)} />
      <InfoItem label="전기 단기차입금" value={formatAmount(financials.priorPeriodShortTermDebt)} />
      <InfoItem label="영업현금흐름" value={formatAmount(financials.operatingCashFlow)} wide />
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

function resolveCompany(companies, query) {
  const normalized = normalize(query);
  return companies.find((company) => normalize(company.companyName) === normalized)
    || companies.find((company) => normalize(company.companyName).includes(normalized))
    || companies.find((company) => company.companyId === query.trim());
}

function findPlanId(companies, selectedId, domain) {
  const selected = companies.find((company) => company.companyId === selectedId);
  return selected?.plans?.find((plan) => plan.domain === domain)?.planId || null;
}

function gapDomain(gapType) {
  if (gapType === 'FUNDING_PLAN_GAP') return 'FUNDING';
  if (gapType === 'FX_BUSINESS_GAP') return 'FX';
  return 'INVESTMENT';
}

function inferPlanStatus(answerText) {
  const normalized = normalize(answerText);
  if (normalized.includes('없') || normalized.includes('아니')) return 'NOT_APPLICABLE';
  if (normalized.includes('완료')) return 'COMPLETED';
  if (normalized.includes('취소') || normalized.includes('철회')) return 'CANCELED';
  if (normalized.includes('진행')) return 'IN_PROGRESS';
  if (normalized.includes('검토')) return 'REVIEW';
  return 'PLANNED';
}

function normalize(value) {
  return String(value || '').replace(/\s+/g, '').toLowerCase();
}

function formatDateTime(value) {
  if (!value) return '일시 미확인';
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}

function formatAmount(amount) {
  if (amount === null || amount === undefined) return '확인된 정보 없음';
  return `${new Intl.NumberFormat('ko-KR').format(amount)} 원`;
}

export default App;