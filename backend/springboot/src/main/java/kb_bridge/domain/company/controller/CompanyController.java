package kb_bridge.domain.company.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

import kb_bridge.domain.company.entity.AnalysisComparisonResponse;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.CompanySearchRequest;
import kb_bridge.domain.company.entity.CompanySearchResponse;
import kb_bridge.domain.company.entity.ConsultationQuestion;
import kb_bridge.domain.company.entity.ConsultationRecord;
import kb_bridge.domain.company.entity.ConsultationRecordRequest;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.domain.company.entity.QuestionAnswerRequest;
import kb_bridge.domain.company.service.ConsultationRecordService;
import kb_bridge.domain.company.service.CompanyService;
import kb_bridge.domain.company.service.CompanySearchService;
import kb_bridge.external.dart.dto.DartCompanyResponse;

@RestController
@RequestMapping("/api/companies")
@CrossOrigin(origins = "http://localhost:3000")
public class CompanyController {

    private final CompanyService companyService;
    private final ConsultationRecordService consultationRecordService;
    private final CompanySearchService companySearchService;

    public CompanyController(
            CompanyService companyService,
            ConsultationRecordService consultationRecordService,
            CompanySearchService companySearchService
    ) {
        this.companyService = companyService;
        this.consultationRecordService = consultationRecordService;
        this.companySearchService = companySearchService;
    }

    @GetMapping
    public List<Company> getRmCompanies() {
        return companyService.getRmCompanies();
    }

    @PostMapping("/search")
    public CompanySearchResponse searchCompany(@RequestBody CompanySearchRequest request) {
        return companySearchService.search(request.companyName());
    }

    @GetMapping("/{companyId}/analysis")
    public GapAnalysisResponse analyzeCompany(@PathVariable String companyId) {
        return companyService.analyzeCompany(companyId);
    }

    @PostMapping("/{companyId}/comparison")
    public AnalysisComparisonResponse compareCompany(@PathVariable String companyId) {
        return companyService.compareCompany(companyId);
    }

    @GetMapping("/{companyId}/comparison/latest")
    public AnalysisComparisonResponse getLatestComparison(@PathVariable String companyId) {
        return companyService.getLatestComparison(companyId);
    }

    @PutMapping("/questions/{questionId}/answer")
    public ConsultationQuestion saveQuestionAnswer(
            @PathVariable long questionId,
            @RequestBody QuestionAnswerRequest request
    ) {
        return companyService.saveQuestionAnswer(questionId, request.answerText());
    }

    @GetMapping("/{companyId}/consultations")
    public List<ConsultationRecord> getConsultations(@PathVariable String companyId) {
        return consultationRecordService.findByCompanyId(companyId);
    }

    @PostMapping("/{companyId}/consultations")
    public ConsultationRecord saveConsultation(
            @PathVariable String companyId,
            @RequestBody ConsultationRecordRequest request
    ) {
        return consultationRecordService.save(
                companyId,
                request.consultationDate(),
                request.consultationText()
        );
    }

    @GetMapping("/{corpCode}")
    public DartCompanyResponse getCompany(
            @PathVariable String corpCode
    ) {
        return companyService.getCompany(corpCode);
    }
}