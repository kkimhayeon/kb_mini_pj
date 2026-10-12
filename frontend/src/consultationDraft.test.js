import { buildConsultationQuestions, EMPTY_DRAFT } from './consultationDraft';

const questions = [
  { key: 'first', planId: 1, domain: 'INVESTMENT', questionText: '같은 질문' },
  { key: 'second', planId: 1, domain: 'INVESTMENT', questionText: '같은 질문' },
];
const now = '2026-01-01T00:00:00Z';

test('does not infer or confirm plan status from ambiguous answer text', () => {
  const result = buildConsultationQuestions(questions, { first: { answerText: '아직 확정된 것은 아닙니다' } }, now);
  expect(result[0].answer.planFact).toBeNull();
  expect(result[1].answer).toBeNull();
});

test('keeps same-text questions distinct and large decimal amounts exact', () => {
  const result = buildConsultationQuestions(questions, {
    first: { ...EMPTY_DRAFT, answerText: '금액', amount: '999999999999999999.99', confirmed: true },
    second: { ...EMPTY_DRAFT, answerText: '일정', expectedAt: '2027-03-01', expectedPrecision: 'MONTH' },
  }, now);
  expect(result[0].answer.planFact.amount).toBe('999999999999999999.99');
  expect(result[1].answer.planFact).toMatchObject({ expectedAt: '2027-03-01', verificationStatus: 'PENDING' });
});

test.each(['-1', '1e5', '0.001', '1000000000000000000', 'NaN'])('rejects invalid amount %s before submission', (amount) => {
  expect(() => buildConsultationQuestions(questions, { first: { ...EMPTY_DRAFT, answerText: '금액', amount } }, now))
    .toThrow('금액');
});

test('rejects structured facts without their source answer', () => {
  expect(() => buildConsultationQuestions(questions, { first: { ...EMPTY_DRAFT, amount: '100' } }, now)).toThrow('답변 원문');
});
