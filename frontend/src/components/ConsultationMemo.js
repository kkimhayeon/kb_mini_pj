import { useState } from 'react';

function ConsultationMemo() {
  const [memo, setMemo] = useState('');

  function downloadMemo() {
    const content = [
      '상담 메모',
      `작성일시 - ${new Date().toLocaleString('ko-KR')}`,
      '',
      memo.trim() || '(메모 없음)',
    ].join('\n');
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `상담메모-${new Date().toISOString().slice(0, 10)}.txt`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  return (
    <section className="memo-box">
      <br></br>
      <h2>상담 메모</h2>
    
      <textarea
        value={memo}
        onChange={(event) => setMemo(event.target.value)}
        placeholder="상담 내용, 후속 조치, 담당자 메모를 입력하세요."
        rows={7}
      />
      <button type="button" className="secondary-button" onClick={downloadMemo}>
        다운로드 
      </button>
    </section>
  );
}

export default ConsultationMemo;
