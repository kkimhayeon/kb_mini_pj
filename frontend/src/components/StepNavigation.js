function StepNavigation({ step, setStep, analysis, busy, labels }) {
  return (
    <nav className="step-nav" aria-label="분석 단계">
      {labels.map((label, index) => (
        <button
          key={label}
          type="button"
          className={index === step ? 'active' : ''}
          disabled={busy || (index > 0 && !analysis)}
          onClick={() => setStep(index)}
        >
          <span>{index + 1}</span>
          {label}
        </button>
      ))}
    </nav>
  );
}

export default StepNavigation;
