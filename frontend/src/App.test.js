import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import App from './App';
import { getCompanies, getCompanyAnalysis } from './api';

const companies = [{
  companyId: '1', companyName: '한빛전자', consultationDate: '2026-01-08',
  investmentPlan: '없음', fundingPlan: '없음', foreignBusinessPlan: '없음',
  rmMemo: '', plans: [{ planId: 11, domain: 'INVESTMENT', scope: '없음' }],
}];
const analysis = {
  company: { ...companies[0], investmentPlan: '이전 상담에서 확인한 투자계획' },
  corpCode: '00123456',
  gaps: [{
    gapType: 'INVESTMENT_PLAN_GAP', existingInfo: '없음', latestInfo: '시설투자 관련 공시 확인',
    reason: '추가 확인이 필요합니다.', changeType: 'NEEDS_CONFIRMATION', assessmentStatus: 'PENDING',
    explanationSource: 'Gemini', evidence: [{ source: 'OpenDART', title: '신규시설투자 결정', date: '2026-06-01', url: 'https://dart.fss.or.kr/example' }],
    questions: ['투자 규모는?', '투자 집행 일정은?'],
  }],
  financials: null, message: '1개의 변경 후보',
};
const response = (body) => ({ ok: true, json: async () => body });

beforeEach(() => { global.fetch = jest.fn(); });

async function openAnswers() {
  global.fetch
    .mockResolvedValueOnce(response(companies))
    .mockResolvedValueOnce(response(analysis))
    .mockResolvedValueOnce(response([]));
  render(<App />);
  fireEvent.change(screen.getByLabelText('기업명'), { target: { value: '한빛전자' } });
  await waitFor(() => expect(screen.getByRole('button', { name: '분석 시작' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '분석 시작' }));
  expect(await screen.findByText('이전 상담에서 확인한 투자계획')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /Gap 및 질문/ }));
  expect(screen.getByText('신규시설투자 결정')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '답변 입력하기' }));
  return screen.getAllByRole('article');
}

test('saves raw answers and reviewed fields in one request, without per-answer PATCH calls', async () => {
  const cards = await openAnswers();
  fireEvent.change(within(cards[0]).getByLabelText('답변 원문'), { target: { value: '100억원' } });
  fireEvent.click(within(cards[0]).getByText('계획 정보 입력 및 RM 확인 (선택)'));
  fireEvent.change(within(cards[0]).getByLabelText('금액 (선택한 통화 단위)'), { target: { value: '10000000000' } });
  fireEvent.change(within(cards[0]).getByLabelText('계획 상태'), { target: { value: 'PLANNED' } });
  const confirmation = within(cards[0]).getByRole('checkbox');
  expect(confirmation).not.toBeChecked();
  fireEvent.click(confirmation);

  fireEvent.change(within(cards[1]).getByLabelText('답변 원문'), { target: { value: '내년 3월' } });
  global.fetch.mockResolvedValueOnce(response({
    consultationId: 1, consultedAt: '2026-01-01T00:00:00Z', memo: '상담 기록', questions: [],
  }));
  fireEvent.click(screen.getByRole('button', { name: 'DB에 답변 저장' }));
  expect(await screen.findByText(/상담과 답변을 모두 저장했습니다/)).toBeInTheDocument();

  expect(global.fetch).toHaveBeenCalledTimes(4);
  const [url, options] = global.fetch.mock.calls[3];
  expect(url).toBe('http://localhost:8080/api/companies/1/consultations');
  expect(options.method).toBe('POST');
  const payload = JSON.parse(options.body);
  expect(payload.questions[0].answer.planFact).toMatchObject({
    amount: '10000000000', currency: 'KRW', planStatus: 'PLANNED',
    verificationStatus: 'RM_CONFIRMED', rmConfirmed: true,
  });
  expect(payload.questions[1].answer).toMatchObject({ answerText: '내년 3월', planFact: null });
});

test('editing a reviewed answer clears confirmation; a failed save retains the draft', async () => {
  const cards = await openAnswers();
  const card = within(cards[0]);
  fireEvent.change(card.getByLabelText('답변 원문'), { target: { value: '100억원' } });
  fireEvent.click(card.getByText('계획 정보 입력 및 RM 확인 (선택)'));
  fireEvent.change(card.getByLabelText('금액 (선택한 통화 단위)'), { target: { value: '10000000000' } });
  fireEvent.click(card.getByRole('checkbox'));
  fireEvent.change(card.getByLabelText('답변 원문'), { target: { value: '금액 재확인 필요' } });
  expect(card.getByRole('checkbox')).not.toBeChecked();

  global.fetch.mockResolvedValueOnce({ ok: false, status: 400, json: async () => ({ message: '저장 검증 실패' }) });
  fireEvent.click(screen.getByRole('button', { name: 'DB에 답변 저장' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('저장 검증 실패');
  expect(card.getByLabelText('답변 원문')).toHaveValue('금액 재확인 필요');
  expect(JSON.parse(global.fetch.mock.calls[3][1].body).questions[0].answer.planFact)
    .toMatchObject({ verificationStatus: 'PENDING', rmConfirmed: false });
});

test('blocks editing and duplicate submits while saving', async () => {
  const cards = await openAnswers();
  const answer = within(cards[0]).getByLabelText('답변 원문');
  fireEvent.change(answer, { target: { value: '아직 확정된 것은 아닙니다' } });
  let finish;
  global.fetch.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  fireEvent.click(screen.getByRole('button', { name: 'DB에 답변 저장' }));
  expect(answer).toBeDisabled();
  expect(screen.getByRole('button', { name: '저장 중' })).toBeDisabled();
  expect(screen.getByRole('button', { name: /기업 입력/ })).toBeDisabled();
  finish(response({ consultationId: 1, consultedAt: '2026-01-01T00:00:00Z', questions: [] }));
  await waitFor(() => expect(screen.queryByRole('button', { name: '저장 중' })).not.toBeInTheDocument());
  expect(global.fetch).toHaveBeenCalledTimes(4);
});

test('shows an explicit message when RM companies cannot be loaded', async () => {
  global.fetch.mockResolvedValueOnce({ ok: false, status: 502, json: async () => ({ message: '기업 목록 조회 실패' }) });
  render(<App />);
  expect(await screen.findByRole('alert')).toHaveTextContent('기업 목록 조회 실패');
});

test('encodes the company id and propagates API errors', async () => {
  global.fetch.mockResolvedValueOnce({ ok: false, status: 502, json: async () => ({ message: '외부 서비스 오류' }) });
  await expect(getCompanyAnalysis('company/1')).rejects.toThrow('외부 서비스 오류');
  expect(global.fetch).toHaveBeenCalledWith('http://localhost:8080/api/companies/company%2F1/analysis');
});

test('rejects a company-list payload that does not match the backend DTO', async () => {
  global.fetch.mockResolvedValueOnce(response({ companies: [] }));
  await expect(getCompanies()).rejects.toThrow('기업 목록 응답 형식이 백엔드 API 계약과 다릅니다.');
});

test('keeps analysis visible when consultation history fails and avoids a false no-change message', async () => {
  const limited = {
    ...analysis, gaps: [], outcome: 'INCONCLUSIVE', scopeLimited: true,
    message: '자료 조회 실패로 판단이 제한됩니다.',
    sources: [
      { source: 'FINANCIALS', status: 'NOT_REQUESTED', message: '이번 조사에서 재무정보를 요청하지 않았습니다.' },
      { source: 'DISCLOSURES', status: 'FAILED', message: '공시 조회 실패' },
    ],
  };
  global.fetch.mockResolvedValueOnce(response(companies))
    .mockResolvedValueOnce(response(limited))
    .mockRejectedValueOnce(new Error('history unavailable'));
  render(<App />);
  fireEvent.change(screen.getByLabelText('기업명'), { target: { value: '한빛전자' } });
  await waitFor(() => expect(screen.getByRole('button', { name: '분석 시작' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '분석 시작' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('상담 이력을 불러오지 못했습니다');
  expect(screen.getByText('이전 상담에서 확인한 투자계획')).toBeInTheDocument();
  expect(screen.queryByText('아직 저장된 상담 질문과 답변이 없습니다.')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /Gap 및 질문/ }));
  expect(screen.getByRole('heading', { name: '자료 부족으로 판단 제한' })).toBeInTheDocument();
  expect(screen.queryByText('확인된 Gap 없음')).not.toBeInTheDocument();
});


test('keeps questions and answers for multiple projects in the same domain separate', async () => {
  const multiCompany = { ...companies[0], plans: [
    { planId: 11, domain: 'INVESTMENT', scope: '서울물류센터' },
    { planId: 12, domain: 'INVESTMENT', scope: '부산물류센터' },
  ] };
  const multiAnalysis = { ...analysis, company: multiCompany, gaps: [
    { ...analysis.gaps[0], planId: 12, questions: ['부산 투자 규모는?'] },
    { ...analysis.gaps[0], planId: 11, questions: ['서울 투자 규모는?'] },
  ] };
  global.fetch.mockResolvedValueOnce(response([multiCompany]))
    .mockResolvedValueOnce(response(multiAnalysis)).mockResolvedValueOnce(response([]));
  render(<App />);
  fireEvent.change(screen.getByLabelText('기업명'), { target: { value: '한빛전자' } });
  await waitFor(() => expect(screen.getByRole('button', { name: '분석 시작' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '분석 시작' }));
  await screen.findByRole('button', { name: '변경사항과 질문 보기' });
  fireEvent.click(screen.getByRole('button', { name: /Gap 및 질문/ }));
  fireEvent.click(screen.getByRole('button', { name: '답변 입력하기' }));
  const inputs = screen.getAllByLabelText('답변 원문');
  fireEvent.change(inputs[0], { target: { value: '부산 150억원' } });
  fireEvent.change(inputs[1], { target: { value: '서울 200억원' } });
  global.fetch.mockResolvedValueOnce(response({
    consultationId: 1, consultedAt: '2026-01-01T00:00:00Z', questions: [],
  }));
  fireEvent.click(screen.getByRole('button', { name: 'DB에 답변 저장' }));
  await screen.findByText(/상담과 답변을 모두 저장했습니다/);
  const payload = JSON.parse(global.fetch.mock.calls[3][1].body);
  expect(payload.questions.map(q => q.planId)).toEqual([12, 11]);
  expect(payload.questions.map(q => q.answer.answerText)).toEqual(['부산 150억원', '서울 200억원']);
});
