export function formatDateTime(value) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export function formatAmount(amount, currency) {
  if (amount === null || amount === undefined || amount === '') return '-';
  return `${Number(amount).toLocaleString('ko-KR')} ${currency || ''}`.trim();
}
