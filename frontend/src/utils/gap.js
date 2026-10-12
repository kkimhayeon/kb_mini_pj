export function summarizeGaps(gaps = []) {
  return gaps.reduce((summary, gap) => {
    const key = gap.changeType || 'UNKNOWN';
    summary[key] = (summary[key] || 0) + 1;
    return summary;
  }, {});
}

export function gapDomain(gapType) {
  if (gapType === 'INVESTMENT_PLAN_GAP') return 'INVESTMENT';
  if (gapType === 'FUNDING_PLAN_GAP') return 'FUNDING';
  return 'FX';
}

export function badgeClass(changeType) {
  return String(changeType || '').toLowerCase().replaceAll('_', '-');
}

export function judgementSentence(gap) {
  if (gap.changeType === 'CONFLICT') return gap.reason || '기존 정보와 공개정보가 다릅니다.';
  if (gap.changeType === 'NEEDS_CONFIRMATION') return '상담을 통해 최신 상태를 확인해야 합니다.';
  return gap.reason || '공개정보를 기반으로 확인이 필요합니다.';
}
