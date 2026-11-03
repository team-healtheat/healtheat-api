package com.healtheat.healtheat_api.mealrecord;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MealRecordRetentionService {
    private final EntityManager entities;
    MealRecordRetentionService(EntityManager entities) { this.entities=entities; }
    @Transactional
    public int deleteExpired(LocalDate today) {
        LocalDate cutoff=MealRecordPolicy.retentionStart(today);
        entities.createQuery("delete from MealRecordItem i where i.mealRecordId in (select r.id from MealRecord r where r.mealDate < :cutoff)")
            .setParameter("cutoff",cutoff).executeUpdate();
        return entities.createQuery("delete from MealRecord r where r.mealDate < :cutoff").setParameter("cutoff",cutoff).executeUpdate();
    }
}
