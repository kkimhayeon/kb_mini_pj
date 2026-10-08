package kb_bridge.domain.company.service;

import java.util.List;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kb_bridge.domain.company.entity.ConsultationRecord;

@Service
public class ConsultationRecordService {

    private final JdbcTemplate jdbcTemplate;

    public ConsultationRecordService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ConsultationRecord> findByCompanyId(String companyId) {
        long id = parseCompanyId(companyId);
        return jdbcTemplate.query("""
                SELECT consultation_record_id, company_id, consultation_date, consultation_text, created_at
                FROM consultation_record
                WHERE company_id = ?
                ORDER BY consultation_date DESC, consultation_record_id DESC
                """,
                (resultSet, rowNumber) -> new ConsultationRecord(
                        resultSet.getLong("consultation_record_id"),
                        Long.toString(resultSet.getLong("company_id")),
                        resultSet.getDate("consultation_date").toLocalDate(),
                        resultSet.getString("consultation_text"),
                        resultSet.getTimestamp("created_at").toLocalDateTime()
                ),
                id
        );
    }

    @Transactional
    public ConsultationRecord save(String companyId, LocalDate consultationDate, String consultationText) {
        long id = parseCompanyId(companyId);
        if (consultationDate == null) {
            throw new IllegalArgumentException("Consultation date is required.");
        }
        if (consultationText == null || consultationText.isBlank()) {
            throw new IllegalArgumentException("Consultation text must not be blank.");
        }
        String text = consultationText.trim();
        if (text.length() > 20000) {
            throw new IllegalArgumentException("Consultation text must not exceed 20000 characters.");
        }
        List<ConsultationRecord> saved = jdbcTemplate.query("""
                INSERT INTO consultation_record (company_id, consultation_date, consultation_text)
                SELECT company_id, ?, ?
                FROM company
                WHERE company_id = ? AND is_active = TRUE
                RETURNING consultation_record_id, company_id, consultation_date, consultation_text, created_at
                """,
                (resultSet, rowNumber) -> new ConsultationRecord(
                        resultSet.getLong("consultation_record_id"),
                        Long.toString(resultSet.getLong("company_id")),
                        resultSet.getDate("consultation_date").toLocalDate(),
                        resultSet.getString("consultation_text"),
                        resultSet.getTimestamp("created_at").toLocalDateTime()
                ),
                java.sql.Date.valueOf(consultationDate),
                text,
                id
        );
        if (saved.isEmpty()) {
            throw new IllegalArgumentException("Unknown or inactive RM company id: " + companyId);
        }
        jdbcTemplate.update(
                "UPDATE company SET consultation_date = ? WHERE company_id = ?",
                java.sql.Date.valueOf(consultationDate),
                id
        );
        return saved.get(0);
    }

    private long parseCompanyId(String companyId) {
        try {
            return Long.parseLong(companyId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected a database-generated numeric company id.", exception);
        }
    }
}
