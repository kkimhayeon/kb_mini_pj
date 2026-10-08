import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from './App';
import { getCompanies, getCompanyAnalysis } from './api';

const companies = [{
  companyId: '1',
  corpCode: '00123456',
  companyName: '한빛전자',
  consultationDate: '2026-01-08',
  plans: [{
    planId: 'investment-1',
    domain: 'INVESTMENT',
    scope: null,
    knowledgeStatus: 'EXPLICIT_NO',
    planStatus: 'NOT_APPLICABLE',
    lastConfirmedAt: null,
    evidenceLevel: 'NONE',
  }],
  rmMemo: '현재 신규 설비투자 계획 없음',
}];

beforeEach(() => {
  global.fetch = jest.fn();
});

test('loads RM companies and shows analyzed gap evidence and questions', async () => {
  global.fetch
    .mockResolvedValueOnce({ ok: true, json: async () => companies })
    .mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({}) })
    .mockResolvedValueOnce({ ok: true, json: async () => [] })
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        mode: 'RM_DART',
        companyName: '한빛전자',
        company: companies[0],
        comparison: {
        comparisonId: 42,
        dartOnlySummary: 'DART 공개자료만으로는 상담기록과 비교할 수 없습니다.',
        dartOnlySource: 'OPENAI',
        dartOnlyQuestions: [{
          questionId: 1,
          text: '공시 자료 중 추가로 확인할 부분은 무엇입니까?',
          answer: null,
          answeredAt: null,
        }],
        dbDartQuestions: [{
          questionId: 2,
          text: '투자 집행 일정은 어떻게 됩니까?',
          answer: null,
          answeredAt: null,
          planId: 'investment-1',
        }],
        dbDartAnalysis: {
        company: companies[0],
        corpCode: '00123456',
        analyzedAt: '2026-06-02',
        searchedFrom: '2026-01-08',
        collectionStatus: 'SUCCESS',
        companyStatus: 'SUCCESS',
        disclosureStatus: 'SUCCESS',
        financialStatus: 'SUCCESS',
        collectionMessages: [],
        assessments: [{
          planId: 'investment-1',
          domain: 'INVESTMENT',
          scope: null,
          comparedFrom: '2026-01-08',
          status: 'ACTION_REQUIRED',
          changeType: 'CONTRADICTION',
          priority: 'PRIORITY_1',
          summary: '공시 원문에서 투자결정 근거 확인',
          evidence: [{
            source: 'OpenDART',
            title: '신규시설투자 결정',
            date: '2026-06-01',
            url: 'https://dart.fss.or.kr/example',
            receiptNumber: '20260601000001',
            correctionStatus: 'NOT_CORRECTION',
            excerpt: '생산시설 투자를 결정하였습니다.',
          }],
        }],
        gaps: [{
          gapType: 'INVESTMENT_PLAN_GAP',
          planId: 'investment-1',
          changeType: 'CONTRADICTION',
          priority: 'PRIORITY_1',
          existingInfo: '없음',
          latestInfo: '시설투자 관련 공시 확인',
          reason: '기존 정보와 최신 정보가 일치하지 않습니다.',
          explanationSource: 'OpenAI',
          evidence: [],
          questions: ['투자 집행 일정은 어떻게 됩니까?'],
        }],
        financials: {
          period: '2025',
          revenue: 1000000,
          operatingProfit: 100000,
          shortTermDebt: 50000,
          priorPeriodShortTermDebt: 40000,
          operatingCashFlow: 20000,
        },
        message: '1개의 고객정보 업데이트 항목을 확인했습니다.',
        },
        },
      }),
    })
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        consultationRecordId: 12,
        companyId: '1',
        consultationDate: '2026-06-02',
        consultationText: '고객이 신규 설비 투자 계획을 확인했습니다.',
        createdAt: '2026-06-02T10:00:00',
      }),
    })
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        questionId: 2,
        text: '투자 집행 일정은 어떻게 됩니까?',
        answer: '다음 상담에서 규모를 확인합니다.',
        answeredAt: '2026-06-02T10:00:00',
        planId: 'investment-1',
      }),
    });

  render(<App />);
  expect((await screen.findAllByText('한빛전자')).length).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole('button', { name: 'AI·DB 비교 분석' }));

  expect(await screen.findByText('OpenAI + DART')).toBeInTheDocument();
  expect(screen.getByText('DART 공개자료만으로는 상담기록과 비교할 수 없습니다.')).toBeInTheDocument();
  expect(screen.getByRole('tablist', { name: '분석 결과 항목' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'AI·DB 비교' })).toHaveAttribute('aria-selected', 'true');
  expect(screen.getByRole('tabpanel', { name: 'AI·DB 비교' })).toBeVisible();
  expect(document.getElementById('analysis-panel-rm')).not.toBeVisible();
  expect(screen.getByText('OpenAI 생성')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('tab', { name: 'RM 사전정보' }));
  expect(await screen.findByText('투자계획 · 명시적 없음')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', { name: '상담 이력' }));
  fireEvent.change(screen.getByLabelText('상담 내용'), {
    target: { value: '고객이 신규 설비 투자 계획을 확인했습니다.' },
  });
  fireEvent.click(screen.getByRole('button', { name: '상담 내역 저장' }));
  expect(await screen.findByText('고객이 신규 설비 투자 계획을 확인했습니다.')).toBeInTheDocument();
  expect(await screen.findByRole('status')).toHaveTextContent('상담 내역을 DB에 저장했습니다.');

  fireEvent.click(screen.getByRole('tab', { name: 'AI·DB 비교' }));
  expect(screen.getByText('투자 집행 일정은 어떻게 됩니까?')).toBeInTheDocument();
  const dbAnswer = screen.getAllByLabelText('상담 답변')[1];
  fireEvent.change(dbAnswer, { target: { value: '다음 상담에서 규모를 확인합니다.' } });
  fireEvent.click(dbAnswer.closest('form').querySelector('button[type="submit"]'));
  expect(await screen.findByText('저장됨 2026-06-02T10:00:00')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('tab', { name: '항목별 분석' }));
  expect(await screen.findByText((content) => content.includes('기존 정보와 상충')
    && content.includes('우선 확인'))).toBeInTheDocument();
  expect(screen.getByText('신규시설투자 결정')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', { name: '공시·재무' }));
  expect(screen.getByText((content) => content.includes('OpenDART 기업코드')
    && content.includes('00123456'))).toBeInTheDocument();
  expect(screen.getByText('전기 단기차입금')).toBeInTheDocument();
  expect(screen.getByText('40,000 원')).toBeInTheDocument();

  await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(6));
  expect(global.fetch).toHaveBeenNthCalledWith(1, '/api/companies');
  expect(global.fetch).toHaveBeenNthCalledWith(2, '/api/companies/1/comparison/latest');
  expect(global.fetch).toHaveBeenNthCalledWith(3, '/api/companies/1/consultations');
  expect(global.fetch).toHaveBeenNthCalledWith(
    4,
    '/api/companies/search',
    expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ companyName: '한빛전자' }),
    })
  );
  expect(global.fetch).toHaveBeenNthCalledWith(
    5,
    '/api/companies/1/consultations',
    expect.objectContaining({
      method: 'POST',
      body: expect.stringContaining('"consultationText":"고객이 신규 설비 투자 계획을 확인했습니다."'),
    })
  );
  expect(global.fetch).toHaveBeenNthCalledWith(
    6,
    '/api/companies/questions/2/answer',
    expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ answerText: '다음 상담에서 규모를 확인합니다.' }),
    })
  );
});

test('saves consultation input and shows it again after reloading the company', async () => {
  const savedRecord = {
    consultationRecordId: 33,
    companyId: '1',
    consultationDate: '2026-10-08',
    consultationText: '고객이 다음 분기 설비 투자 계획을 확인했습니다.',
    createdAt: '2026-10-08T11:00:00',
  };
  global.fetch
    .mockResolvedValueOnce({ ok: true, json: async () => companies })
    .mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({}) })
    .mockResolvedValueOnce({ ok: true, json: async () => [] })
    .mockResolvedValueOnce({ ok: true, json: async () => savedRecord });

  const firstRender = render(<App />);
  await screen.findByText('저장된 상담 이력이 없습니다.');
  fireEvent.change(screen.getByLabelText('상담일'), {
    target: { value: savedRecord.consultationDate },
  });
  fireEvent.change(screen.getByLabelText('상담 내용'), {
    target: { value: savedRecord.consultationText },
  });
  fireEvent.click(screen.getByRole('button', { name: '상담 내역 저장' }));
  expect(await screen.findByRole('status')).toHaveTextContent('상담 내역을 DB에 저장했습니다.');
  expect(await screen.findByText(savedRecord.consultationText)).toBeInTheDocument();
  firstRender.unmount();

  global.fetch
    .mockResolvedValueOnce({ ok: true, json: async () => companies })
    .mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({}) })
    .mockResolvedValueOnce({ ok: true, json: async () => [savedRecord] });
  render(<App />);

  expect(await screen.findByText(savedRecord.consultationText)).toBeInTheDocument();
  expect(global.fetch).toHaveBeenCalledWith('/api/companies/1/consultations', expect.anything());
});

test('shows OpenDART information when a searched company has no RM database record', async () => {
  global.fetch
    .mockResolvedValueOnce({ ok: true, json: async () => companies })
    .mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({}) })
    .mockResolvedValueOnce({ ok: true, json: async () => [] })
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        mode: 'DART_ONLY',
        companyName: 'DART 등록 회사',
        corpCode: '00999999',
        message: 'DART에는 있지만 RM DB에는 없습니다.',
        dartCompany: {
          corpNameEng: 'DART Listed Company',
          stockName: '등록 회사',
          stockCode: '12345',
          ceoName: '대표자',
          corpClass: 'K',
          adres: '서울',
          establishedDate: '20000101',
        },
        financials: {
          period: '2025',
          revenue: 500000,
          operatingProfit: 30000,
          shortTermDebt: null,
          totalDebt: null,
          operatingCashFlow: null,
        },
        financialStatus: 'SUCCESS',
        disclosureStatus: 'SUCCESS',
        disclosures: [{
          receiptNumber: '20261008000001',
          date: '2026-10-08',
          title: '유상증자 결정',
          url: 'https://dart.fss.or.kr/example',
        }],
        collectionMessages: [],
        aiStatus: 'NOT_REQUESTED',
        aiSummary: null,
        aiQuestions: [],
        analyzedAt: '2026-10-08',
      }),
    });

  render(<App />);
  await screen.findByRole('heading', { name: '분석할 기업을 입력하세요' });
  fireEvent.change(screen.getByLabelText('분석할 기업명 입력'), {
    target: { value: 'DART 등록 회사' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'AI·DB 비교 분석' }));

  expect(await screen.findByRole('heading', { name: 'DART 등록 회사 분석 결과' })).toBeInTheDocument();
  expect(screen.queryByText('OpenAI 공개정보 요약')).not.toBeInTheDocument();
  expect(screen.getByText('DART Listed Company')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '유상증자 결정' })).toHaveAttribute(
    'href', 'https://dart.fss.or.kr/example');
  expect(global.fetch).toHaveBeenNthCalledWith(
    4,
    '/api/companies/search',
    expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ companyName: 'DART 등록 회사' }),
    })
  );
});

test('shows a freshness warning for GPT-only company reference results', async () => {
  global.fetch
    .mockResolvedValueOnce({ ok: true, json: async () => companies })
    .mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({}) })
    .mockResolvedValueOnce({ ok: true, json: async () => [] })
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        mode: 'GPT_REFERENCE',
        companyName: 'DART 미등록 회사',
        message: 'GPT 참고정보이며 최신성이 보장되지 않습니다.',
        collectionMessages: ['OpenDART 미확인, 회사별 사실은 검증되지 않았습니다.'],
        aiStatus: 'OPENAI',
        aiSummary: '공식 자료를 확인해야 합니다.',
        aiQuestions: ['사업자 정보를 확인하셨나요?'],
      }),
    });

  render(<App />);
  await screen.findByRole('heading', { name: '분석할 기업을 입력하세요' });
  fireEvent.change(screen.getByLabelText('분석할 기업명 입력'), {
    target: { value: 'DART 미등록 회사' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'AI·DB 비교 분석' }));

  expect(await screen.findByRole('heading', { name: 'DART 미등록 회사 분석 결과' })).toBeInTheDocument();
  expect(screen.getByText(/GPT 참고 답변입니다/)).toBeInTheDocument();
  expect(screen.getByText('공식 자료를 확인해야 합니다.')).toBeInTheDocument();
  expect(screen.getByText('사업자 정보를 확인하셨나요?')).toBeInTheDocument();
});

test('shows an explicit message when RM companies cannot be loaded', async () => {
  global.fetch.mockResolvedValueOnce({
    ok: false,
    status: 502,
    json: async () => ({ message: 'RM 사전정보 CSV를 읽을 수 없습니다.' }),
  });
  render(<App />);
  expect(await screen.findByRole('alert')).toHaveTextContent('RM 사전정보 CSV를 읽을 수 없습니다.');
});

test('requests the backend analysis route and reports backend API errors', async () => {
  global.fetch.mockResolvedValueOnce({
    ok: false,
    status: 502,
    json: async () => ({ message: 'OPEN_API_KEY must be configured.' }),
  });

  await expect(getCompanyAnalysis('company/1')).rejects.toThrow(
    'OPEN_API_KEY must be configured.'
  );
  expect(global.fetch).toHaveBeenCalledWith(
    '/api/companies/company%2F1/analysis'
  );
});

test('rejects a company-list payload that does not match the backend DTO', async () => {
  global.fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => ({ companies: [] }),
  });

  await expect(getCompanies()).rejects.toThrow(
    '기업 목록 응답 형식이 백엔드 API 계약과 다릅니다.'
  );
});
