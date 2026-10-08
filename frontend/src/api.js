const API_BASE_URL = process.env.REACT_APP_API_BASE_URL || 'http://localhost:8080';

function apiUrl(path) {
  return `${API_BASE_URL}${path}`;
}

async function getJson(path, failureMessage) {
  let response;
  try {
    response = await fetch(apiUrl(path));
  } catch (error) {
    throw new Error(`백엔드에 연결할 수 없습니다. ${error.message}`);
  }

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
    throw new Error(body?.message || `${failureMessage} (${response.status}).`);
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

async function sendJson(path, method, body, failureMessage) {
  let response;
  try {
    response = await fetch(apiUrl(path), {
      method,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
      },
      body: JSON.stringify(body),
    });
  } catch (error) {
    throw new Error(`백엔드에 연결할 수 없습니다. ${error.message}`);
  }

  let responseBody;
  try {
    responseBody = await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(`${failureMessage} (${response.status}).`);
    }
    throw new Error('백엔드가 올바른 JSON 응답을 반환하지 않았습니다.');
  }

  if (!response.ok) {
    throw new Error(responseBody?.message || `${failureMessage} (${response.status}).`);
  }
  return responseBody;
}

export async function getConsultations(companyId) {
  const consultations = await getJson(
    `/api/companies/${encodeURIComponent(companyId)}/consultations`,
    '상담 이력 조회에 실패했습니다'
  );
  if (!Array.isArray(consultations)) {
    throw new Error('상담 이력 응답 형식이 올바르지 않습니다.');
  }
  return consultations;
}

export async function createConsultation(companyId, payload) {
  return sendJson(
    `/api/companies/${encodeURIComponent(companyId)}/consultations`,
    'POST',
    payload,
    '상담 저장에 실패했습니다'
  );
}

export async function answerQuestion(qaId, payload) {
  return sendJson(
    `/api/consultation-qa/${encodeURIComponent(qaId)}/answer`,
    'PATCH',
    payload,
    '답변 저장에 실패했습니다'
  );
}