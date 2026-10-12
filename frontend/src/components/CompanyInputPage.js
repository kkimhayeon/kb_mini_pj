function CompanyInputPage({ query, setQuery, companies, loadingCompanies, loadingAnalysis, onSubmit, labels }) {
  return (
    <div className="single-card input-page">
      <p className="eyebrow">Corporate Client Gap Agent</p>
      <h1>{labels.title}</h1>
      <form className="company-search" onSubmit={onSubmit}>
        <label htmlFor="company-query">{labels.field}</label>
        <div className="search-row">
          <input
            id="company-query"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            list="company-options"
            placeholder={labels.placeholder}
            autoComplete="off"
          />
          <button type="submit" disabled={loadingCompanies || loadingAnalysis || !query.trim()}>
            {loadingAnalysis ? labels.loading : labels.submit}
          </button>
        </div>
        <datalist id="company-options">
          {companies.map((company) => (
            <option key={company.companyId} value={company.companyName} />
          ))}
        </datalist>
      </form>
      <p className="hint">{labels.hint}</p>
    </div>
  );
}

export default CompanyInputPage;
