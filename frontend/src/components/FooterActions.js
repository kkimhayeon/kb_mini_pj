function FooterActions({ onPrev, onNext, onSave, nextLabel, saveLabel, saveDisabled, prevDisabled }) {
  return (
    <div className="footer-actions">
      {onPrev && <button type="button" className="secondary-button" onClick={onPrev} disabled={prevDisabled}>이전</button>}
      <div />
      {onNext && <button type="button" onClick={onNext}>{nextLabel}</button>}
      {onSave && <button type="button" onClick={onSave} disabled={saveDisabled}>{saveLabel}</button>}
    </div>
  );
}

export default FooterActions;
