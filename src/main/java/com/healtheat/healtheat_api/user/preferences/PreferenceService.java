package com.healtheat.healtheat_api.user.preferences;

import com.healtheat.healtheat_api.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PreferenceService {
    private final EntityManager entities;
    public PreferenceService(EntityManager entities) { this.entities = entities; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreferenceDtos.Response get(long userId) {
        var preferences = entities.createQuery("select p from UserPreference p where p.userId = :userId", UserPreference.class)
            .setParameter("userId", userId).getResultStream().findFirst().orElse(null);
        var allergens = entities.createQuery("select a.allergenName from UserAllergy a where a.userId = :userId and a.allergenName is not null order by a.id", String.class)
            .setParameter("userId", userId).getResultList();
        return new PreferenceDtos.Response(preferences == null ? null : preferences.allowDiningOut,
            preferences == null || preferences.likedFoods == null ? List.of() : preferences.likedFoods,
            preferences == null || preferences.dislikedFoods == null ? List.of() : preferences.dislikedFoods,
            preferences == null || preferences.dietaryPreferences == null ? List.of() : preferences.dietaryPreferences,
            preferences == null ? PreferenceDtos.AllergyStatus.UNANSWERED : preferences.allergyStatus, allergens);
    }

    @Transactional
    public PreferenceDtos.Response update(long userId, PreferenceDtos.UpdateRequest request) {
        if ((request.allergyStatus() == null) != (request.allergens() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allergyStatus and allergens must be provided together");
        if (request.allergyStatus() == PreferenceDtos.AllergyStatus.UNANSWERED)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose no allergies or provide allergy items");
        List<String> allergens = normalize(request.allergens());
        if (allergens != null && ((request.allergyStatus() == PreferenceDtos.AllergyStatus.PRESENT) != !allergens.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRESENT requires allergens; other states require an empty list");
        List<String> liked = normalize(request.likedFoods());
        List<String> disliked = normalize(request.dislikedFoods());
        List<String> dietary = normalize(request.dietaryPreferences());
        if (entities.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE) == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        var preference = entities.createQuery("select p from UserPreference p where p.userId = :userId", UserPreference.class)
            .setParameter("userId", userId).getResultStream().findFirst().orElse(null);
        if (preference == null) { preference = new UserPreference(userId); entities.persist(preference); }
        if (request.allowDiningOut() != null) preference.allowDiningOut = request.allowDiningOut();
        if (liked != null) preference.likedFoods = liked;
        if (disliked != null) preference.dislikedFoods = disliked;
        if (dietary != null) preference.dietaryPreferences = dietary;
        if (allergens != null) {
            preference.allergyStatus = request.allergyStatus();
            entities.createQuery("delete from UserAllergy a where a.userId = :userId").setParameter("userId", userId).executeUpdate();
            for (String allergen : allergens) entities.persist(new UserAllergy(userId, allergen));
        }
        entities.flush();
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
}
