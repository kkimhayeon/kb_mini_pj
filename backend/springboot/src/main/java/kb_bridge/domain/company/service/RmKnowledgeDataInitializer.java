package kb_bridge.domain.company.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class RmKnowledgeDataInitializer implements CommandLineRunner {

    private final RmKnowledgeService rmKnowledgeService;

    public RmKnowledgeDataInitializer(RmKnowledgeService rmKnowledgeService) {
        this.rmKnowledgeService = rmKnowledgeService;
    }

    @Override
    public void run(String... args) {
        rmKnowledgeService.seedFromCsvIfEmpty();
    }
}
