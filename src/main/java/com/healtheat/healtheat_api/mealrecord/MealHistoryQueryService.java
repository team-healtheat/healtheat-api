package com.healtheat.healtheat_api.mealrecord;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Only actual records, not unconsumed recommendations. No user identifiers or unconfirmed amounts. */
@Service
public class MealHistoryQueryService {
    public record RecentMeal(LocalDate mealDate, String mealType, List<String> foods) {}
    private final EntityManager entities;
    public MealHistoryQueryService(EntityManager entities) { this.entities = entities; }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<RecentMeal> recent(long userId) { return recentAt(userId, MealRecordPolicy.today()); }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<RecentMeal> recentAt(long userId, LocalDate today) {
        var records=entities.createQuery("""
            select r from MealRecord r where r.userId = :userId and r.mealDate >= :fromDate and r.mealDate <= :today
            order by r.mealDate desc, r.id desc
            """,MealRecord.class).setParameter("userId",userId).setParameter("fromDate",MealRecordPolicy.recommendationStart(today))
            .setParameter("today",today).getResultList();
        return records.stream().map(r -> new RecentMeal(r.mealDate,r.mealType,
            entities.createQuery("select i.foodName from MealRecordItem i where i.mealRecordId = :id order by i.id",String.class)
                .setParameter("id",r.id).getResultList())).toList();
    }
}
