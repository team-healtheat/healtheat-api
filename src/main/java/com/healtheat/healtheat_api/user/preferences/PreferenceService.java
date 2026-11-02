package com.healtheat.healtheat_api.user.preferences;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PreferenceService {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public PreferenceService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreferenceDtos.Response get(long userId) {
        List<PreferenceDtos.Response> rows = db.query("""
            SELECT allow_dining_out, liked_foods, disliked_foods, dietary_preferences, allergy_status
            FROM user_preferences WHERE user_id = ?
            """, (rs, row) -> new PreferenceDtos.Response((Boolean) rs.getObject("allow_dining_out"),
                parse(rs.getString("liked_foods")), parse(rs.getString("disliked_foods")),
                parse(rs.getString("dietary_preferences")), PreferenceDtos.AllergyStatus.valueOf(rs.getString("allergy_status")), List.of()), userId);
        var value = rows.isEmpty() ? new PreferenceDtos.Response(null, List.of(), List.of(), List.of(),
            PreferenceDtos.AllergyStatus.UNANSWERED, List.of()) : rows.get(0);
        List<String> allergens = db.queryForList("SELECT allergen_name FROM user_allergies WHERE user_id = ? AND allergen_name IS NOT NULL ORDER BY id", String.class, userId);
        return new PreferenceDtos.Response(value.allowDiningOut(), value.likedFoods(), value.dislikedFoods(), value.dietaryPreferences(), value.allergyStatus(), allergens);
    }

    @Transactional
    public PreferenceDtos.Response update(long userId, PreferenceDtos.UpdateRequest request) {
        if ((request.allergyStatus() == null) != (request.allergens() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allergyStatus and allergens must be provided together");
        List<String> allergens = normalize(request.allergens());
        if (allergens != null && ((request.allergyStatus() == PreferenceDtos.AllergyStatus.PRESENT) != !allergens.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRESENT requires allergens; other states require an empty list");
        List<String> liked = normalize(request.likedFoods());
        List<String> disliked = normalize(request.dislikedFoods());
        List<String> dietary = normalize(request.dietaryPreferences());
        if (db.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        db.update("INSERT INTO user_preferences(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId);
        if (request.allowDiningOut() != null) db.update("UPDATE user_preferences SET allow_dining_out = ? WHERE user_id = ?", request.allowDiningOut(), userId);
        if (liked != null) db.update("UPDATE user_preferences SET liked_foods = CAST(? AS jsonb) WHERE user_id = ?", serialize(liked), userId);
        if (disliked != null) db.update("UPDATE user_preferences SET disliked_foods = CAST(? AS jsonb) WHERE user_id = ?", serialize(disliked), userId);
        if (dietary != null) db.update("UPDATE user_preferences SET dietary_preferences = CAST(? AS jsonb) WHERE user_id = ?", serialize(dietary), userId);
        if (allergens != null) {
            db.update("UPDATE user_preferences SET allergy_status = ? WHERE user_id = ?", request.allergyStatus().name(), userId);
            db.update("DELETE FROM user_allergies WHERE user_id = ?", userId);
            for (String allergen : allergens)
                db.update("INSERT INTO user_allergies(user_id, allergen_name, status) VALUES (?, ?, 'PRESENT')", userId, allergen);
        }
        return get(userId);
    }
    private List<String> normalize(List<String> values) {
        if (values == null) return null;
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String trimmed = value.trim();
            if (!seen.add(trimmed.toLowerCase(Locale.ROOT))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate list item");
            result.add(trimmed);
        }
        return result;
    }
    private List<String> parse(String value) {
        if (value == null) return List.of();
        try { return json.readValue(value, STRINGS); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid stored preference format", ex); }
    }
    private String serialize(List<String> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not encode preference data", ex); }
    }
}
