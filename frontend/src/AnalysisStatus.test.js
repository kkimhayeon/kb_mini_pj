import { render, screen } from '@testing-library/react';
import AnalysisStatus from './AnalysisStatus';

test.each([
  ['INCONCLUSIVE', '자료 부족으로 판단 제한'],
  ['NO_DATA', '분석할 공개정보 없음'],
  ['NO_CHANGE_EVIDENCE', '조회 범위 내 변경 근거 없음'],
])('shows the distinct outcome %s', (outcome, label) => {
  render(<AnalysisStatus analysis={{ outcome, sources: [], gaps: [] }} />);
  expect(screen.getByText(label)).toBeInTheDocument();
});

test('shows failed, skipped, truncated sources and Gemini fallback separately', () => {
  render(<AnalysisStatus analysis={{
    outcome: 'GAPS_FOUND', scopeLimited: true,
    sources: [
      { source: 'FINANCIALS', status: 'NOT_REQUESTED', message: '이번 조사에서 요청하지 않았습니다.' },
      { source: 'DISCLOSURES', status: 'PARTIAL', message: '추가 페이지가 있습니다.' },
      { source: 'COMPANY', status: 'FAILED', message: '기업 조회 실패' },
    ],
    gaps: [{ explanationSource: 'Rule fallback (Gemini failed)' }],
  }} />);
  expect(screen.getByText('재무정보: 이번 조사 대상 아님')).toBeInTheDocument();
  expect(screen.getByText('공시정보: 일부 자료만 조회')).toBeInTheDocument();
  expect(screen.getByText('기업 확인: 조회 실패')).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('규칙 판정은 유지됩니다');
});
