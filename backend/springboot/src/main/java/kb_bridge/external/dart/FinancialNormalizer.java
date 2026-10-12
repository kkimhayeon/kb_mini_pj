package kb_bridge.external.dart;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.external.dart.dto.DartFinancialResponse.Item;

final class FinancialNormalizer {
    private FinancialNormalizer() {}
    static FinancialSnapshot normalize(int year, String basis, List<Item> items) {
        items = items.stream().filter(java.util.Objects::nonNull).toList();
        Item revenue = find(items, Set.of("IS", "CIS"), List.of("ifrs-full_Revenue"),
                List.of("매출액", "영업수익", "수익"));
        Item profit = find(items, Set.of("IS", "CIS"), List.of("dart_OperatingIncomeLoss"),
                List.of("영업이익", "영업이익(손실)", "영업손익"));
        Item debt = find(items, Set.of("BS"), List.of("dart_ShortTermBorrowings"),
                List.of("단기차입금"));
        Item cash = find(items, Set.of("CF"), List.of("ifrs-full_CashFlowsFromUsedInOperatingActivities"),
                List.of("영업활동현금흐름", "영업활동으로인한현금흐름", "영업활동으로부터의현금흐름"));
        List<String> currencies = items.stream().map(Item::currency).filter(v -> v != null && v.matches("[A-Z]{3}"))
                .distinct().toList();
        String currency = currencies.size() == 1 ? currencies.getFirst() : null;
        String receipt = items.stream().map(Item::receiptNumber).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
        return new FinancialSnapshot(Integer.toString(year),
                amount(revenue, currency, false), amount(profit, currency, false),
                amount(debt, currency, false), amount(debt, currency, true), amount(cash, currency, false),
                basis, currency, receipt);
    }
    private static Item find(List<Item> items, Set<String> sections, List<String> ids, List<String> names) {
        List<Item> eligible = items.stream().filter(v -> v.statementType() != null && sections.contains(v.statementType())).toList();
        for (String id : ids) {
            List<Item> matches = eligible.stream().filter(v -> id.equals(v.accountId())).toList();
            if (!matches.isEmpty()) return unique(matches);
        }
        for (String name : names) {
            List<Item> matches = eligible.stream()
                    .filter(v -> v.accountName() != null && v.accountName().replaceAll("\\s+", "").equals(name)).toList();
            if (!matches.isEmpty()) return unique(matches);
        }
        return null;
    }
    private static Item unique(List<Item> matches) {
        // Prefer a missing value over silently choosing between conflicting accounting rows.
        return matches.stream().map(v -> List.of(String.valueOf(v.currentAmount()), String.valueOf(v.priorAmount()),
                String.valueOf(v.currency()))).distinct().count() == 1 ? matches.getFirst() : null;
    }
    private static BigDecimal amount(Item item, String currency, boolean prior) {
        if (item == null || currency == null || !currency.equals(item.currency())) return null;
        String value = prior ? item.priorAmount() : item.currentAmount();
        if (value == null || value.isBlank() || "-".equals(value.trim())) return null;
        String cleaned = value.replace(",", "").trim();
        if (cleaned.matches("\\([0-9.]+\\)")) cleaned = "-" + cleaned.substring(1, cleaned.length() - 1);
        try { return new BigDecimal(cleaned); }
        catch (NumberFormatException exception) { return null; }
    }
}
