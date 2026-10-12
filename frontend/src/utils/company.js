export function normalize(value) {
  return String(value || '').toLowerCase().replace(/[\s()\-]/g, '');
}

export function resolveCompany(companies, query) {
  const normalizedQuery = normalize(query);
  return companies.find((company) => normalize(company.companyName) === normalizedQuery)
    || companies.find((company) => normalize(company.companyName).includes(normalizedQuery));
}

export function findPlanId(companies, companyId, domain) {
  const company = companies.find((item) => item.companyId === companyId);
  return company?.plans?.find((plan) => plan.domain === domain)?.planId ?? null;
}
