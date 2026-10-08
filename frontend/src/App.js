import { useEffect, useState } from 'react';
import {
  getCompanies,
  getConsultations,
  getLatestComparison,
  searchCompany,
  saveConsultation,
  saveQuestionAnswer,
} from './api';
import './App.css';

function App() {
  const [companies, setCompanies] = useState([]);
  const [searchName, setSearchName] = useState('');
  const [searchResult, setSearchResult] = useState(null);
  const [selectedId, setSelectedId] = useState('');
  const [analysis, setAnalysis] = useState(null);
  const [comparison, setComparison] = useState(null);
  const [activeTab, setActiveTab] = useState('comparison');
  const [consultations, setConsultations] = useState([]);
  const [consultationDate, setConsultationDate] = useState(todayDate());
  const [consultationText, setConsultationText] = useState('');
  const [savingConsultation, setSavingConsultation] = useState(false);
  const [consultationMessage, setConsultationMessage] = useState('');
  const [loadingCompanies, setLoadingCompanies] = useState(true);
  const [loadingAnalysis, setLoadingAnalysis] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    getCompanies()
      .then((items) => {
        setCompanies(items);
        if (items.length > 0) {
          setSelectedId(items[0].companyId);
          setSearchName(items[0].companyName);
        }
      })
      .catch((loadError) => setError(loadError.message))
      .finally(() => setLoadingCompanies(false));
  }, []);

  useEffect(() => {
    if (!selectedId) return undefined;
    let active = true;
    getLatestComparison(selectedId)
      .then((savedComparison) => {
        if (!active) return;
        setComparison(savedComparison);
        setAnalysis(savedComparison?.dbDartAnalysis || null);
        if (savedComparison) setActiveTab('comparison');
      })
      .catch((loadError) => {
        if (active) setError(loadError.message);
      });
    getConsultations(selectedId)
      .then((records) => {
        if (active) setConsultations(records);
      })
      .catch((loadError) => {
        if (active) setConsultationMessage(loadError.message);
      });
    return () => {
      active = false;
    };
  }, [selectedId]);

  async function saveConsultationRecord(event) {
    event.preventDefault();
    if (!selectedId) return;

    setSavingConsultation(true);
    setConsultationMessage('');
    try {
      const saved = await saveConsultation(selectedId, consultationDate, consultationText);
      setConsultations((current) => [saved, ...current]);
      setConsultationText('');
      setCompanies((current) => current.map((company) => (
        company.companyId === selectedId
          ? { ...company, consultationDate: saved.consultationDate }
          : company
      )));
      setConsultationMessage('상담 내역을 DB에 저장했습니다.');
    } catch (saveError) {
      setConsultationMessage(saveError.message);
    } finally {
      setSavingConsultation(false);
    }
  }

  async function analyzeSelectedCompany(event) {
    event.preventDefault();
    if (!searchName.trim()) return;

    setLoadingAnalysis(true);
    setError('');
    setAnalysis(null);
    setComparison(null);
    setSearchResult(null);
    try {
      const result = await searchCompany(searchName);
      setSearchResult(result);
      setSearchName(result.companyName || searchName.trim());
      if (result.mode === 'RM_DART') {
        setSelectedId(result.company.companyId);
        setComparison(result.comparison);
        setAnalysis(result.comparison.dbDartAnalysis);
        setActiveTab('comparison');
      } else {
        setSelectedId('');
        setConsultations([]);
        setConsultationMessage('');
      }
    } catch (analysisError) {
      setError(analysisError.message);
    } finally {
      setLoadingAnalysis(false);
    }
  }

  const selectedCompany = companies.find((company) => company.companyId === selectedId);

  return (
    <main className="page-shell">
      <header className="page-header">
        <div className="brand-mark" aria-hidden="true">CG</div>
        <div>
          <p className="eyebrow">CORPORATE CLIENT INTELLIGENCE</p>
          <h1>기업 고객정보 Gap Agent</h1>
          <p className="subtitle">RM 사전정보와 최신 OpenDART 공개정보를 비교합니다.</p>
        </div>
      </header>

      <section className="selection-panel" aria-labelledby="company-selection-title">
        <div>
          <p className="eyebrow">CLIENT REVIEW</p>
          <h2 id="company-selection-title">분석할 기업을 입력하세요</h2>
        </div>
        <form onSubmit={analyzeSelectedCompany} className="company-form">
          <label className="visually-hidden" htmlFor="company-search">분석할 기업명 입력</label>
          <input
            id="company-search"
            type="search"
            list="rm-company-suggestions"
            value={searchName}
            onChange={(event) => setSearchName(event.target.value)}
            placeholder="회사명을 입력하세요"
            maxLength={200}
            disabled={loadingCompanies}
            required
          />
          <datalist id="rm-company-suggestions">
            {companies.map((company) => (
              <option key={company.companyId} value={company.companyName} />
            ))}
          </datalist>
          <button type="submit" disabled={!searchName.trim() || loadingAnalysis}>
            {loadingAnalysis ? '비교 분석 중...' : 'AI·DB 비교 분석'}
          </button>
        </form>
      </section>

      {comparison && (
        <nav className="analysis-tabs" role="tablist" aria-label="분석 결과 항목">
          {[
            ['comparison', 'AI·DB 비교'],
            ['rm', 'RM 사전정보'],
            ['consultation', '상담 이력'],
            ['public', '공시·재무'],
            ['gaps', '항목별 분석'],
          ].map(([tabId, label]) => (
            <button
              key={tabId}
              id={`analysis-tab-${tabId}`}
              type="button"
              role="tab"
              aria-selected={activeTab === tabId}
              aria-controls={`analysis-panel-${tabId}`}
              tabIndex={activeTab === tabId ? 0 : -1}
              onClick={() => setActiveTab(tabId)}
            >
              {label}
            </button>
          ))}
        </nav>
      )}

      {error && <p className="error-message" role="alert">{error}</p>}
      {loadingCompanies && <p className="empty-state">RM 기업정보를 불러오는 중입니다...</p>}
      {searchResult && searchResult.mode !== 'RM_DART' && (
        <CompanySearchResult result={searchResult} />
      )}

      {comparison && (
        <section
          className="comparison-dashboard analysis-tab-panel"
          id="analysis-panel-comparison"
          role="tabpanel"
          aria-label="AI·DB 비교"
          aria-labelledby="analysis-tab-comparison"
          hidden={activeTab !== 'comparison'}
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">RESULT COMPARISON</p>
              <h2 id="comparison-title">AI 분석 비교 대시보드</h2>
            </div>
            <p className="comparison-id">비교 실행 #{comparison.comparisonId}</p>
          </div>
          <p className="comparison-note">
            두 분석은 동일한 OpenDART 수집 자료를 사용합니다. 왼쪽 OpenAI 입력에는 RM 상담 DB를 포함하지 않고,
            오른쪽은 RM 계획 DB와 공개자료를 규칙으로 대조합니다. 공시 조회 범위는 DB 분석 기준으로 수집됩니다.
          </p>
          <div className="comparison-columns">
            <article className="comparison-arm ai-arm">
              <p className="eyebrow">DART ONLY · OPENAI</p>
              <h3>OpenAI + DART</h3>
              <p>{comparison.dartOnlySummary}</p>
              <span className={`status-badge ${comparison.dartOnlySource === 'OPENAI' ? '' : 'warning'}`}>
                {comparison.dartOnlySource === 'OPENAI'
                  ? 'OpenAI 생성'
                  : 'AI 결과 없음'}
              </span>
              <QuestionList
                questions={comparison.dartOnlyQuestions}
                onAnswerSaved={(savedQuestion) => updateSavedQuestion(setComparison, 'dartOnlyQuestions', savedQuestion)}
              />
            </article>
            <article className="comparison-arm db-arm">
              <p className="eyebrow">RM DATABASE + DART</p>
              <h3>DB + DART 규칙 분석</h3>
              <p>{comparison.dbDartAnalysis.message}</p>
              <div className="comparison-metrics">
                <span>평가 계획 {comparison.dbDartAnalysis.assessments?.length || 0}건</span>
                <span>Gap {comparison.dbDartAnalysis.gaps?.length || 0}건</span>
                <span>조회 {collectionStatusLabel(comparison.dbDartAnalysis.collectionStatus)}</span>
              </div>
              <QuestionList
                questions={comparison.dbDartQuestions}
                onAnswerSaved={(savedQuestion) => updateSavedQuestion(setComparison, 'dbDartQuestions', savedQuestion)}
              />
            </article>
          </div>
        </section>
      )}

      {selectedCompany && (
        <section
          className="rm-panel analysis-tab-panel"
          id="analysis-panel-rm"
          role="tabpanel"
          aria-label="RM 사전정보"
          aria-labelledby="analysis-tab-rm"
          hidden={Boolean(comparison) && activeTab !== 'rm'}
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">EXISTING RM KNOWLEDGE</p>
              <h2 id="rm-info-title">{selectedCompany.companyName} <span>RM 사전정보</span></h2>
            </div>
            <p className="consultation-date">
              최근 상담일 <strong>{selectedCompany.consultationDate}</strong>
            </p>
          </div>
          <dl className="rm-grid">
            {(selectedCompany.plans || []).map((plan) => (
              <div key={plan.planId}>
                <dt>{domainLabel(plan.domain)} · {knowledgeStatusLabel(plan.knowledgeStatus)}</dt>
                <dd>{plan.scope || planStatusLabel(plan.planStatus)}</dd>
                <small>
                  항목 확인일 {plan.lastConfirmedAt || '미기록'}
                  {' · '}근거 {evidenceLevelLabel(plan.evidenceLevel)}
                  {' · '}출처 {plan.source || '미기록'}
                  {plan.amount !== null && plan.amount !== undefined
                    ? ` · 규모 ${formatAmount(plan.amount, plan.currency || '')}`
                    : ''}
                  {plan.expectedAt ? ` · 시기 ${plan.expectedAt}` : ''}
                  {plan.reviewDueAt ? ` · 재확인 예정 ${plan.reviewDueAt}` : ''}
                </small>
              </div>
            ))}
            <div className="memo"><dt>RM 메모</dt><dd>{selectedCompany.rmMemo || '확인할 수 없음'}</dd></div>
          </dl>
        </section>
      )}

      {selectedCompany && (
        <section
          className="consultation-panel analysis-tab-panel"
          id="analysis-panel-consultation"
          role="tabpanel"
          aria-label="상담 이력"
          aria-labelledby="analysis-tab-consultation"
          hidden={Boolean(comparison) && activeTab !== 'consultation'}
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">RM CONSULTATION LOG</p>
              <h2 id="consultation-title">상담 내역 입력</h2>
            </div>
          </div>
          <form className="consultation-form" onSubmit={saveConsultationRecord}>
            <label htmlFor="consultation-date">상담일</label>
            <input
              id="consultation-date"
              type="date"
              value={consultationDate}
              onChange={(event) => setConsultationDate(event.target.value)}
              required
            />
            <label htmlFor="consultation-text">상담 내용</label>
            <textarea
              id="consultation-text"
              value={consultationText}
              onChange={(event) => setConsultationText(event.target.value)}
              placeholder="고객이 확인한 계획, 변경사항, 합의 내용과 후속 조치 등을 입력하세요."
              maxLength={20000}
              rows={5}
              required
            />
            <div className="consultation-form-footer">
              <span>{consultationText.length.toLocaleString('ko-KR')} / 20,000자</span>
              <button type="submit" disabled={savingConsultation || !consultationText.trim() || !consultationDate}>
                {savingConsultation ? '저장 중...' : '상담 내역 저장'}
              </button>
            </div>
            {consultationMessage && (
              <p className="consultation-feedback" role="status">{consultationMessage}</p>
            )}
          </form>
          <div className="consultation-history">
            <h3>저장된 상담 이력</h3>
            {consultations.length > 0 ? (
              <ol>
                {consultations.map((record) => (
                  <li key={record.consultationRecordId}>
                    <time dateTime={record.consultationDate}>{record.consultationDate}</time>
                    <p>{record.consultationText}</p>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="empty-questions">저장된 상담 이력이 없습니다.</p>
            )}
          </div>
        </section>
      )}

      {analysis && (
        <section
          className="analysis-section analysis-tab-panel"
          id="analysis-panel-public"
          role="tabpanel"
          aria-label="공시·재무"
          aria-labelledby="analysis-tab-public"
          hidden={Boolean(comparison) && activeTab !== 'public'}
        >
          <div className="analysis-heading">
            <div>
              <p className="eyebrow">LATEST PUBLIC INFORMATION</p>
              <h2 id="analysis-title">분석 결과</h2>
              <p className="corp-code">
                OpenDART 기업코드 {analysis.corpCode}
                {' · '}분석일 {analysis.analyzedAt || '확인 불가'}
                {' · '}비교 시작 {analysis.searchedFrom || '최근 12개월'}
              </p>
            </div>
            <span className={analysis.collectionStatus === 'SUCCESS' ? 'status-badge' : 'status-badge warning'}>
              {collectionStatusLabel(analysis.collectionStatus)}
            </span>
          </div>
          <p className="analysis-message">{analysis.message}</p>
          <p className="retrieval-details">
            기업정보 {retrievalStatusLabel(analysis.companyStatus)}
            {' · '}
            공시 {retrievalStatusLabel(analysis.disclosureStatus)}
            {' · '}재무 {retrievalStatusLabel(analysis.financialStatus)}
          </p>
          {analysis.collectionMessages?.length > 0 && (
            <div className="data-notice collection-notice" role="status">
              <strong>자료 조회 상태</strong>
              <ul>{analysis.collectionMessages.map((message) => <li key={message}>{message}</li>)}</ul>
            </div>
          )}

          {analysis.financials ? (
            <div className="financial-panel">
              <h3>최근 재무정보 <span>{analysis.financials.period} 사업연도</span></h3>
              <dl className="financial-grid">
                <div><dt>매출액</dt><dd>{formatAmount(analysis.financials.revenue)}</dd></div>
                <div><dt>영업이익</dt><dd>{formatAmount(analysis.financials.operatingProfit)}</dd></div>
                <div><dt>단기차입금</dt><dd>{formatAmount(analysis.financials.shortTermDebt)}</dd></div>
                <div><dt>전기 단기차입금</dt><dd>{formatAmount(analysis.financials.priorPeriodShortTermDebt)}</dd></div>
                <div><dt>총차입금</dt><dd>{formatAmount(analysis.financials.totalDebt)}</dd></div>
                <div><dt>전기 총차입금</dt><dd>{formatAmount(analysis.financials.priorPeriodTotalDebt)}</dd></div>
                <div><dt>영업활동 현금흐름</dt><dd>{formatAmount(analysis.financials.operatingCashFlow)}</dd></div>
              </dl>
            </div>
          ) : (
            <p className="data-notice">
              {analysis.financialStatus === 'FAILED'
                ? '재무정보 조회에 실패했습니다. 이 결과를 변경 근거 미발견으로 해석하지 마세요.'
                : analysis.financialStatus === 'NOT_REQUESTED'
                  ? '이 분석에서는 재무정보를 조회하지 않았습니다.'
                  : '비교 가능한 최근 재무정보가 없습니다.'}
            </p>
          )}

          {analysis.financialSignals?.map((signal) => (
            <div className="data-notice financial-signal" key={signal.type}>
              <strong>재무 조사 신호 · Gap 확정 아님</strong>
              <p>{signal.message}</p>
            </div>
          ))}
        </section>
      )}

      {analysis && (
        <section
          className="analysis-section analysis-tab-panel"
          id="analysis-panel-gaps"
          role="tabpanel"
          aria-label="항목별 분석"
          aria-labelledby="analysis-tab-gaps"
          hidden={Boolean(comparison) && activeTab !== 'gaps'}
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">GAP ASSESSMENT</p>
              <h2>항목별 평가 결과</h2>
            </div>
          </div>
          {(analysis.assessments || []).length > 0 ? analysis.assessments.map((assessment) => {
            const gap = analysis.gaps.find((item) => item.planId === assessment.planId);
            return (
            <article className={`gap-card assessment-${assessment.status.toLowerCase()}`} key={assessment.planId}>
              <div className="gap-title-row">
                <span className="gap-icon" aria-hidden="true">!</span>
                <div>
                  <h3>
                    {domainLabel(assessment.domain)} · {assessment.scope || knowledgeStatusLabel(assessment.knowledgeStatus)}
                  </h3>
                  <span className={`assessment-badge priority-${assessment.priority.toLowerCase()}`}>
                    {changeTypeLabel(assessment.changeType)}
                    {assessment.priority !== 'NONE' && ` · ${priorityLabel(assessment.priority)}`}
                  </span>
                </div>
              </div>
              <p className="assessment-summary">{assessment.summary}</p>
              <p className="comparison-period">비교 시작일 {assessment.comparedFrom || '확인 불가'}</p>
              {gap && (
              <div className="comparison-grid">
                <div><h4>기존 RM 정보</h4><p>{gap.existingInfo || '미기록'}</p></div>
                <div><h4>최신 근거</h4><p>{gap.latestInfo}</p></div>
              </div>
              )}
              {gap && (
              <div className="reason-panel">
                <h4>Gap 발생 이유 <span className="generation-source">{gap.explanationSource}</span></h4>
                <p>{gap.reason}</p>
              </div>
              )}
              <div className="evidence-panel">
                <h4>원문 근거</h4>
                {assessment.evidence.length > 0 ? (
                  <ul>
                    {assessment.evidence.map((evidence) => (
                      <li key={evidence.receiptNumber || `${evidence.date}-${evidence.title}`}>
                        <span>
                          {evidence.source} · {evidence.date || '날짜 확인 필요'}
                          {evidence.correctionStatus && evidence.correctionStatus !== 'NOT_CORRECTION'
                            ? ` · 정정 연결 ${evidence.correctionStatus === 'LINKED' ? '완료' : '확인 필요'}`
                            : ''}
                        </span>
                        {evidence.url
                          ? <a href={evidence.url} target="_blank" rel="noreferrer">{evidence.title}</a>
                          : <span>{evidence.title}</span>}
                        {evidence.excerpt && <p className="evidence-excerpt">{evidence.excerpt}</p>}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p>이 항목에 연결된 직접 공시 근거가 없습니다.</p>
                )}
              </div>
            </article>
          );
          }) : (
            <p className="empty-state no-gap">항목별 평가 결과가 없습니다.</p>
          )}
        </section>
      )}
    </main>
  );
}

function CompanySearchResult({ result }) {
  const isGptReference = result.mode === 'GPT_REFERENCE' || result.mode === 'GPT_UNAVAILABLE';
  const dartCompany = result.dartCompany;

  return (
    <section className="search-result-panel" aria-labelledby="search-result-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">{isGptReference ? 'GPT REFERENCE' : 'OPENDART PUBLIC INFORMATION'}</p>
          <h2 id="search-result-title">{result.companyName} 분석 결과</h2>
        </div>
        <span className={`status-badge ${result.mode === 'GPT_UNAVAILABLE' ? 'warning' : ''}`}>
          {result.mode === 'DART_ONLY'
            ? 'DART 공개정보'
            : result.mode === 'GPT_REFERENCE'
              ? 'GPT 참고정보'
              : 'GPT 사용 불가'}
        </span>
      </div>
      <p className="analysis-message">{result.message}</p>

      {result.collectionMessages?.length > 0 && (
        <div className="data-notice collection-notice" role="status">
          <strong>자료 조회 상태</strong>
          <ul>{result.collectionMessages.map((message) => <li key={message}>{message}</li>)}</ul>
        </div>
      )}

      {isGptReference ? (
        <>
          <div className="data-notice gpt-disclaimer">
            OpenDART에서 확인되지 않은 회사명에 대한 GPT 참고 답변입니다. 회사별 사실과 최신 정보는 공식 자료로 확인하세요.
          </div>
          {result.aiSummary && <p className="search-ai-summary">{result.aiSummary}</p>}
          {result.aiQuestions?.length > 0 && (
            <div className="search-ai-questions">
              <h3>확인해 볼 질문</h3>
              <ul>{result.aiQuestions.map((question) => <li key={question}>{question}</li>)}</ul>
            </div>
          )}
        </>
      ) : (
        <>
          <p className="retrieval-details">
            OpenDART 기업코드 {result.corpCode}
            {' · '}분석일 {result.analyzedAt}
            {' · '}재무 {retrievalStatusLabel(result.financialStatus)}
            {' · '}공시 {retrievalStatusLabel(result.disclosureStatus)}
          </p>
          {dartCompany && (
            <dl className="dart-company-details">
              <div><dt>영문명</dt><dd>{dartCompany.corpNameEng || '확인할 수 없음'}</dd></div>
              <div><dt>종목명 / 종목코드</dt><dd>{[dartCompany.stockName, dartCompany.stockCode].filter(Boolean).join(' / ') || '확인할 수 없음'}</dd></div>
              <div><dt>대표자</dt><dd>{dartCompany.ceoName || '확인할 수 없음'}</dd></div>
              <div><dt>기업 구분</dt><dd>{dartCompany.corpClass || '확인할 수 없음'}</dd></div>
              <div><dt>주소</dt><dd>{dartCompany.adres || '확인할 수 없음'}</dd></div>
              <div><dt>설립일</dt><dd>{dartCompany.establishedDate || '확인할 수 없음'}</dd></div>
            </dl>
          )}
          {result.financials && (
            <div className="financial-panel">
              <h3>최근 재무정보 <span>{result.financials.period} 사업연도</span></h3>
              <dl className="financial-grid">
                <div><dt>매출액</dt><dd>{formatAmount(result.financials.revenue)}</dd></div>
                <div><dt>영업이익</dt><dd>{formatAmount(result.financials.operatingProfit)}</dd></div>
                <div><dt>단기차입금</dt><dd>{formatAmount(result.financials.shortTermDebt)}</dd></div>
                <div><dt>총차입금</dt><dd>{formatAmount(result.financials.totalDebt)}</dd></div>
                <div><dt>영업활동 현금흐름</dt><dd>{formatAmount(result.financials.operatingCashFlow)}</dd></div>
              </dl>
            </div>
          )}
          {result.aiSummary && (
            <article className="search-ai-summary">
              <h3>OpenAI 공개정보 요약</h3>
              <p>{result.aiSummary}</p>
              {result.aiQuestions?.length > 0 && (
                <ul>{result.aiQuestions.map((question) => <li key={question}>{question}</li>)}</ul>
              )}
            </article>
          )}
          <div className="search-disclosures">
            <h3>관련 공시 ({result.disclosures?.length || 0}건)</h3>
            {result.disclosures?.length > 0 ? (
              <ul>
                {result.disclosures.map((disclosure) => (
                  <li key={disclosure.receiptNumber}>
                    <span>{disclosure.date || '날짜 확인 필요'}</span>
                    <a href={disclosure.url} target="_blank" rel="noreferrer">{disclosure.title}</a>
                  </li>
                ))}
              </ul>
            ) : (
              <p>조회 범위에서 분석 대상으로 분류된 관련 공시가 없습니다.</p>
            )}
          </div>
        </>
      )}
    </section>
  );
}

function todayDate() {
  const now = new Date();
  const localDate = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return localDate.toISOString().slice(0, 10);
}

function QuestionList({ questions, onAnswerSaved }) {
  if (questions.length === 0) {
    return <p className="empty-questions">저장된 상담 질문이 없습니다.</p>;
  }
  return (
    <ol className="comparison-questions">
      {questions.map((question) => (
        <li key={question.questionId}>
          <p>{question.text}</p>
          {question.planId && <small>계획 ID {question.planId}</small>}
          <QuestionAnswerField question={question} onAnswerSaved={onAnswerSaved} />
        </li>
      ))}
    </ol>
  );
}

function QuestionAnswerField({ question, onAnswerSaved }) {
  const [answer, setAnswer] = useState(question.answer || '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => setAnswer(question.answer || ''), [question.answer]);

  async function saveAnswer(event) {
    event.preventDefault();
    setSaving(true);
    setError('');
    try {
      onAnswerSaved(await saveQuestionAnswer(question.questionId, answer));
    } catch (saveError) {
      setError(saveError.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="answer-form" onSubmit={saveAnswer}>
      <label htmlFor={`answer-${question.questionId}`}>상담 답변</label>
      <textarea
        id={`answer-${question.questionId}`}
        value={answer}
        onChange={(event) => setAnswer(event.target.value)}
        maxLength={10000}
        rows={2}
      />
      <div className="answer-actions">
        {question.answeredAt && <small>저장됨 {question.answeredAt}</small>}
        <button type="submit" disabled={saving || !answer.trim()}>
          {saving ? '저장 중...' : '답변 저장'}
        </button>
      </div>
      {error && <p className="answer-error" role="alert">{error}</p>}
    </form>
  );
}

function updateSavedQuestion(setComparison, key, savedQuestion) {
  setComparison((current) => ({
    ...current,
    [key]: current[key].map((question) => (
      question.questionId === savedQuestion.questionId ? savedQuestion : question
    )),
  }));
}

function formatAmount(amount, unit = '원') {
  if (amount === null || amount === undefined) return '확인할 수 없음';
  const formatted = new Intl.NumberFormat('ko-KR').format(amount);
  return unit ? `${formatted} ${unit}` : formatted;
}

function domainLabel(domain) {
  return ({
    INVESTMENT: '투자계획',
    FUNDING: '자금조달',
    FOREIGN_BUSINESS: '해외사업·외환',
  })[domain] || domain;
}

function knowledgeStatusLabel(status) {
  return ({
    EXPLICIT_YES: '명시적 있음',
    EXPLICIT_NO: '명시적 없음',
    UNKNOWN: '미확인',
    UNRECORDED: '미기록',
  })[status] || '상태 미확인';
}

function planStatusLabel(status) {
  return ({
    UNDER_REVIEW: '검토 중',
    PLANNED: '예정',
    IN_PROGRESS: '진행 중',
    COMPLETED: '완료',
    CANCELLED: '취소',
    NOT_APPLICABLE: '해당 없음',
    UNKNOWN: '알 수 없음',
  })[status] || '알 수 없음';
}

function evidenceLevelLabel(level) {
  return ({
    DIRECT_CONFIRMATION: '직접 확인',
    DOCUMENT: '문서 확인',
    INDIRECT: '간접 전달',
    NONE: '근거 없음',
  })[level] || '근거 미확인';
}

function changeTypeLabel(changeType) {
  return ({
    CONTRADICTION: '기존 정보와 상충',
    NEW_INFORMATION: '신규 정보',
    PLAN_DETAIL_UPDATE: '계획 구체화',
    PLAN_TERMINATED: '계획 취소·완료',
    ADDITIONAL_CONFIRMATION_REQUIRED: '추가 확인 필요',
    RECONFIRMATION: '재확인 필요',
    NONE: '변경 근거 미발견',
  })[changeType] || '판단 보류';
}

function priorityLabel(priority) {
  return ({
    PRIORITY_1: '우선 확인',
    PRIORITY_2: '다음 확인',
    PRIORITY_3: '세부 갱신',
  })[priority] || '';
}

function collectionStatusLabel(status) {
  return ({
    SUCCESS: '조회 완료',
    PARTIAL_FAILURE: '일부 조회 실패',
    FAILURE: '조회 실패',
  })[status] || '조회 상태 미확인';
}

function retrievalStatusLabel(status) {
  return ({
    SUCCESS: '정상 조회',
    NO_DATA: '조회 완료·자료 없음',
    PARTIAL_FAILURE: '일부 실패',
    FAILED: '조회 실패',
    NOT_REQUESTED: '미조회',
  })[status] || '상태 미확인';
}

export default App;
