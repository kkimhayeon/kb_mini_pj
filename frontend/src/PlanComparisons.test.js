import { render, screen } from '@testing-library/react';
import PlanComparisons from './PlanComparisons';

test('shows auditable before and after values with the original disclosure link', () => {
  render(<PlanComparisons comparisons={[{
    planId: 12, projectName: '서울물류센터', matchStatus: 'NAME_MATCH',
    comparisonStatus: 'DIFFERENCES', message: '동일 사업인지 RM 확인이 필요합니다.',
    changes: [
      { field: 'AMOUNT', before: '100 KRW', after: '150 KRW' },
      { field: 'EXPECTED_AT', before: '2026-03', after: '2026-04-01' },
    ],
    evidence: { title: '유형자산양수결정', url: 'https://dart.fss.or.kr/example',
      details: { sourceField: 'inhdtl_inhprc' } },
  }]} />);
  expect(screen.getByText('대상명 일치 후보 · 금액·일정 차이 확인')).toBeInTheDocument();
  expect(screen.getByText('100 KRW')).toBeInTheDocument();
  expect(screen.getByText('150 KRW')).toBeInTheDocument();
  expect(screen.getByText('2026-03')).toBeInTheDocument();
  expect(screen.getByRole('link')).toHaveAttribute('href', 'https://dart.fss.or.kr/example');
});

test('ambiguous projects show the limitation without fabricated numbers', () => {
  render(<PlanComparisons comparisons={[{
    projectName: '서울물류센터', matchStatus: 'AMBIGUOUS', comparisonStatus: 'INSUFFICIENT',
    message: '동일한 대상명의 계획이 여러 개여서 자동 연결하지 않았습니다.', changes: [],
  }]} />);
  expect(screen.getByText('동일 대상명 계획 여러 개 · 추가 확인 필요')).toBeInTheDocument();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
});
