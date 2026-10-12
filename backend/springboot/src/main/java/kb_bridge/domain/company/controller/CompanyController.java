package kb_bridge.domain.company.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.List;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.domain.company.service.CompanyService;
import kb_bridge.external.dart.dto.DartCompanyResponse;

@RestController
@RequestMapping("/api/companies")
@CrossOrigin(origins = "http://localhost:3000")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    public List<Company> getRmCompanies() {
        return companyService.getRmCompanies();
    }

    @GetMapping("/discovery")
    public GapAnalysisResponse discoverCompany(@RequestParam String name) {
        return companyService.discoverCompany(name);
    }

    @GetMapping("/{companyId}/analysis")
    public GapAnalysisResponse analyzeCompany(@PathVariable String companyId) {
        return companyService.analyzeCompany(companyId);
    }

    @GetMapping("/{corpCode}")
    public DartCompanyResponse getCompany(
            @PathVariable String corpCode
    ) {
        return companyService.getCompany(corpCode);
    }
}
