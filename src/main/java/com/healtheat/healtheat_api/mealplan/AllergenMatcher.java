package com.healtheat.healtheat_api.mealplan;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Limited spelling/alias comparison only; does not establish allergy safety. */
@Component
class AllergenMatcher {
    private static final List<Set<String>> ALIASES = List.of(
        Set.of("새우", "대하", "shrimp", "prawn"),
        Set.of("우유", "milk"),
        Set.of("땅콩", "peanut"),
        Set.of("달걀", "계란", "egg"));
    List<String> patterns(List<String> allergens) {
        List<String> result = new ArrayList<>();
        for (String allergen : allergens) {
            String input = normalize(allergen);
            result.add(input);
            for (var group : ALIASES) if (group.stream().map(this::normalize).anyMatch(input::equals))
                group.stream().map(this::normalize).forEach(result::add);
        }
        return result;
    }
    boolean matches(List<String> patterns, String value) {
        if (value == null) return false;
        String candidate = normalize(value);
        return patterns.stream().anyMatch(p -> !p.isBlank() && candidate.contains(p));
    }
    private String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}\\p{P}]", "");
    }
}
