package kb_bridge.domain.company.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import kb_bridge.domain.company.entity.ConsultationDtos.AnswerQuestionRequest;
import kb_bridge.domain.company.entity.ConsultationDtos.ConsultationResponse;
import kb_bridge.domain.company.entity.ConsultationDtos.CreateConsultationRequest;
import kb_bridge.domain.company.entity.ConsultationDtos.QuestionAnswerResponse;
import kb_bridge.domain.company.service.ConsultationService;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
public class ConsultationController {

    private final ConsultationService consultationService;

    public ConsultationController(ConsultationService consultationService) {
        this.consultationService = consultationService;
    }

    @PostMapping("/companies/{companyId}/consultations")
    public ConsultationResponse createConsultation(
            @PathVariable Long companyId,
            @RequestBody CreateConsultationRequest request
    ) {
        return consultationService.createConsultation(companyId, request);
    }

    @GetMapping("/companies/{companyId}/consultations")
    public List<ConsultationResponse> getConsultations(@PathVariable Long companyId) {
        return consultationService.findConsultations(companyId);
    }

    @PatchMapping("/consultation-qa/{qaId}/answer")
    public QuestionAnswerResponse answerQuestion(
            @PathVariable Long qaId,
            @RequestBody AnswerQuestionRequest request
    ) {
        return consultationService.answerQuestion(qaId, request);
    }
}
