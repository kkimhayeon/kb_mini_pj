async function getJson(path, failureMessage, options = {}) {
  const { notFoundIsEmpty = false, ...fetchOptions } = options;
  let response;
  try {
    response = Object.keys(fetchOptions).length > 0
      ? await fetch(path, fetchOptions)
      : await fetch(path);
  } catch (error) {
    throw new Error(`백엔드에 연결할 수 없습니다. ${error.message}`);
  }
  if (notFoundIsEmpty && response.status === 404) return null;

  let body;
  try {
    body = await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(`${failureMessage} (${response.status}).`);
    }
    throw new Error('백엔드가 올바른 JSON 응답을 반환하지 않았습니다.');
  }

  if (!response.ok) {
    throw new Error(body?.message || body?.detail || `${failureMessage} (${response.status}).`);
  }
  return body;
}

export async function getCompanies() {
  const companies = await getJson('/api/companies', '기업 목록 조회에 실패했습니다');
  if (!Array.isArray(companies)
      || companies.some((company) => !company.companyId || !company.companyName)) {
    throw new Error('기업 목록 응답 형식이 백엔드 API 계약과 다릅니다.');
  }
  return companies;
}

export async function searchCompany(companyName) {
  const result = await getJson(
    '/api/companies/search',
    '기업 검색에 실패했습니다',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ companyName }),
    }
  );
  if (!result || !['RM_DART', 'DART_ONLY', 'GPT_REFERENCE', 'GPT_UNAVAILABLE'].includes(result.mode)) {
    throw new Error('기업 검색 응답 형식이 백엔드 API 계약과 다릅니다.');
  }
  return result;
}

export async function getCompanyAnalysis(companyId) {
  const analysis = await getJson(
    `/api/companies/${encodeURIComponent(companyId)}/analysis`,
    '기업 분석 요청에 실패했습니다'
  );
  if (!analysis || !Array.isArray(analysis.gaps)) {
    throw new Error('기업 분석 응답 형식이 백엔드 API 계약과 다릅니다.');
  }
  return analysis;
}

export async function compareCompany(companyId) {
  const comparison = await getJson(
    `/api/companies/${encodeURIComponent(companyId)}/comparison`,
    '비교 분석 요청에 실패했습니다',
    { method: 'POST' }
  );
  if (!comparison || !comparison.dbDartAnalysis || !Array.isArray(comparison.dartOnlyQuestions)
      || !Array.isArray(comparison.dbDartQuestions)) {
    throw new Error('비교 분석 응답 형식이 백엔드 API 계약과 다릅니다.');
  }
  return comparison;
}

export async function getLatestComparison(companyId) {
  return getJson(
    `/api/companies/${encodeURIComponent(companyId)}/comparison/latest`,
    '저장된 비교 결과 조회에 실패했습니다',
    { notFoundIsEmpty: true }
  );
}

export async function saveQuestionAnswer(questionId, answerText) {
  return getJson(
    `/api/companies/questions/${encodeURIComponent(questionId)}/answer`,
    '상담 답변 저장에 실패했습니다',
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answerText }),
    }
  );
}

export async function getConsultations(companyId) {
  const records = await getJson(
    `/api/companies/${encodeURIComponent(companyId)}/consultations`,
    '상담 이력 조회에 실패했습니다'
  );
  if (!Array.isArray(records)) {
    throw new Error('상담 이력 응답 형식이 백엔드 API 계약과 다릅니다.');
  }
  return records;
}

export async function saveConsultation(companyId, consultationDate, consultationText) {
  return getJson(
    `/api/companies/${encodeURIComponent(companyId)}/consultations`,
    '상담 내역 저장에 실패했습니다',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ consultationDate, consultationText }),
    }
  );
}
