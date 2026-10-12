import AnswerEditor from '../AnswerEditor';
import ConsultationMemo from './ConsultationMemo';
import PageHeader from './PageHeader';
import FooterActions from './FooterActions';
import { DOMAIN_LABELS } from '../constants/labels';
import { formatDateTime } from '../utils/formatting';

function AnswerPage({ questions, answerDrafts, setAnswerDrafts, consultations, saveMessage, savingAnswers, canSave, onPrev, onSave }) {
  return (
    <div className="page-panel">
      <PageHeader eyebrow="PAGE 3" title="질문 답변 저장" description="답변 원문을 기록하고, 다음 분석에 반영할 계획 항목을 입력해 확인해주세요. 입력하지 않은 항목은 기존 정보를 유지합니다." />
      {questions.length === 0 ? (
        <div className="empty-box">
          <h2>분석한 결과로 생성된 질문이 없습니다.</h2>
          </div>
      ) : (
        <div className="answer-list">
          {questions.map((question, index) => (
            <AnswerEditor key={question.key} question={question} number={index + 1} domainLabel={DOMAIN_LABELS[question.domain]} draft={answerDrafts[question.key]} disabled={savingAnswers} onChange={(draft) => setAnswerDrafts((drafts) => ({ ...drafts, [question.key]: draft }))} />
          ))}
        </div>
      )}
      {saveMessage && <p className="success-message">{saveMessage}</p>}
      {!canSave && <p className="notice">DB 미등록 기업의 공개정보 결과입니다. 상담 답변을 저장하려면 기업을 먼저 등록해주세요.</p>}
      <ConsultationMemo />
      {consultations.length > 0 && <section className="saved-history"><h2>최근 DB 저장 이력</h2><ul>{consultations.slice(0, 3).map((consultation) => <li key={consultation.consultationId}><strong>{formatDateTime(consultation.consultedAt)}</strong><span>{consultation.memo || '상담 기록'}</span></li>)}</ul></section>}
      <FooterActions onPrev={onPrev} onSave={onSave} saveLabel={savingAnswers ? '저장 중' : 'DB에 답변 저장'} prevDisabled={savingAnswers} saveDisabled={!canSave || savingAnswers || !questions.some((question) => answerDrafts[question.key]?.answerText?.trim())} />
    </div>
  );
}

export default AnswerPage;
