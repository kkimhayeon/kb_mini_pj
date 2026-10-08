package kb_bridge.domain.company.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.Company.PlanSummary;
import kb_bridge.domain.company.persistence.CompanyEntity;
import kb_bridge.domain.company.persistence.CompanyRepository;
import kb_bridge.domain.company.persistence.RmPlanEntity;
import kb_bridge.domain.company.persistence.RmPlanRepository;

@Service
public class RmKnowledgeService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Path csvPath;
    private final CompanyRepository companyRepository;
    private final RmPlanRepository rmPlanRepository;

    public RmKnowledgeService(
            @Value("${rm.data.path:../../RM_Prior_Knowledge_100.csv}") String csvPath,
            CompanyRepository companyRepository,
            RmPlanRepository rmPlanRepository
    ) {
        this.csvPath = Path.of(csvPath);
        this.companyRepository = companyRepository;
        this.rmPlanRepository = rmPlanRepository;
    }

    @Transactional(readOnly = true)
    public List<Company> findAll() {
        List<CompanyEntity> companies = companyRepository.findAll().stream()
                .sorted(Comparator.comparing(CompanyEntity::getCompanyId))
                .toList();
        Map<Long, List<RmPlanEntity>> plansByCompany = rmPlanRepository.findAll().stream()
                .collect(Collectors.groupingBy(plan -> plan.getCompany().getCompanyId()));
        return companies.stream()
                .map(company -> toCompany(company, plansByCompany.getOrDefault(company.getCompanyId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Company findById(String companyId) {
        long id;
        try {
            id = Long.parseLong(companyId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid RM company id: " + companyId, e);
        }
        CompanyEntity company = companyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown RM company id: " + companyId));
        return toCompany(company, rmPlanRepository.findByCompanyCompanyId(id));
    }

    @Transactional
    public void seedFromCsvIfEmpty() {
        if (companyRepository.count() > 0) {
            return;
        }
        for (Company company : readCsvCompanies()) {
            OffsetDateTime consultedAt = company.consultationDate()
                    .atStartOfDay(SEOUL)
                    .toOffsetDateTime();
            CompanyEntity savedCompany = companyRepository.save(new CompanyEntity(
                    toCorpCode(company.companyId()),
                    company.companyName(),
                    consultedAt
            ));
            rmPlanRepository.save(toPlan(savedCompany, "INVESTMENT", company.investmentPlan(), consultedAt));
            rmPlanRepository.save(toPlan(savedCompany, "FUNDING", company.fundingPlan(), consultedAt));
            rmPlanRepository.save(toPlan(savedCompany, "FX", company.foreignBusinessPlan(), consultedAt));
        }
    }

    private List<Company> readCsvCompanies() {
        try {
            List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new IllegalStateException("RM knowledge CSV is empty: " + csvPath);
            }

            List<Company> companies = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                if (!lines.get(i).isBlank()) {
                    companies.add(toCompany(parseCsvLine(lines.get(i)), i + 1));
                }
            }
            return List.copyOf(companies);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read RM knowledge CSV: " + csvPath, e);
        }
    }

    private Company toCompany(List<String> columns, int lineNumber) {
        if (columns.size() != 7) {
            throw new IllegalStateException(
                    "Expected 7 CSV columns at line " + lineNumber + " in " + csvPath);
        }
        try {
            return new Company(
                    columns.get(0),
                    columns.get(1),
                    LocalDate.parse(columns.get(2)),
                    columns.get(3),
                    columns.get(4),
                    columns.get(5),
                    columns.get(6)
            );
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid RM knowledge CSV at line " + lineNumber, e);
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                columns.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        if (quoted) {
            throw new IllegalStateException("Unclosed quoted value in RM knowledge CSV");
        }
        columns.add(value.toString());
        return columns;
    }

    private Company toCompany(CompanyEntity company, List<RmPlanEntity> plans) {
        Map<String, RmPlanEntity> plansByDomain = plans.stream()
                .collect(Collectors.toMap(RmPlanEntity::getDomain, Function.identity(), (first, ignored) -> first));
        return new Company(
                String.valueOf(company.getCompanyId()),
                company.getCorpCode(),
                company.getCorpName(),
                company.getLastConsultedAt().atZoneSameInstant(SEOUL).toLocalDate(),
                planText(plansByDomain.get("INVESTMENT")),
                planText(plansByDomain.get("FUNDING")),
                planText(plansByDomain.get("FX")),
                plans.stream()
                        .map(RmPlanEntity::getNote)
                        .filter(note -> note != null && !note.isBlank())
                        .findFirst()
                        .orElse(""),
                plans.stream()
                        .map(plan -> new PlanSummary(plan.getPlanId(), plan.getDomain(), plan.getScope()))
                        .toList()
        );
    }

    private RmPlanEntity toPlan(CompanyEntity company, String domain, String planText, OffsetDateTime consultedAt) {
        boolean noPlan = isNoPlan(planText);
        return new RmPlanEntity(
                company,
                domain,
                planText,
                noPlan ? "EXPLICIT_NONE" : "EXPLICIT_YES",
                noPlan ? "NOT_APPLICABLE" : "REVIEW",
                consultedAt,
                "RM_Prior_Knowledge_100.csv",
                "DIRECT",
                "UNVERIFIED",
                domain.equals("INVESTMENT") ? "Seeded from RM mock CSV" : null
        );
    }

    private String planText(RmPlanEntity plan) {
        if (plan == null || plan.getScope() == null) {
            return "";
        }
        return plan.getScope();
    }

    private boolean isNoPlan(String planText) {
        if (planText == null || planText.isBlank()) {
            return false;
        }
        String normalized = planText.replaceAll("\\s+", "");
        return normalized.equals("없음")
                || normalized.contains("계획없음")
                || normalized.contains("없다")
                || normalized.contains("미계획")
                || normalized.equals("?놁쓬")
                || normalized.contains("怨꾪쉷?놁쓬")
                || normalized.contains("?녿떎")
                || normalized.contains("誘멸퀎");
    }

    private String toCorpCode(String csvCompanyId) {
        try {
            return String.format("%08d", Long.parseLong(csvCompanyId));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("CSV company_id must be numeric: " + csvCompanyId, e);
        }
    }
}
