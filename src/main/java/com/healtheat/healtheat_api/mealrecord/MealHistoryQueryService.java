package com.healtheat.healtheat_api.mealrecord;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Only actual records, not unconsumed recommendations. No user identifiers or unconfirmed amounts. */
@Service
public class MealHistoryQueryService {
    public record RecentMeal(LocalDate mealDate, String mealType, List<String> foods) {}
    public record HistoryCount(String foodName, LocalDate from, LocalDate to, long count, boolean limitedToRetention) {}
    private final EntityManager entities;
    public MealHistoryQueryService(EntityManager entities) { this.entities = entities; }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<RecentMeal> recent(long userId) { return recentAt(userId, MealRecordPolicy.today()); }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<RecentMeal> recentAt(long userId, LocalDate today) {
        return recentWithinDaysAt(userId,today,14);
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<RecentMeal> recentWithinDays(long userId, int requestedDays) {
        return recentWithinDaysAt(userId,MealRecordPolicy.today(),requestedDays);
    }
    private List<RecentMeal> recentWithinDaysAt(long userId, LocalDate today, int requestedDays) {
        if (requestedDays<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"기간은 하루 이상이어야 합니다.");
        int days=Math.min(requestedDays,30);
        var records=entities.createQuery("""
            select r from MealRecord r where r.userId = :userId and r.mealDate >= :fromDate and r.mealDate <= :today
            order by r.mealDate desc, r.id desc
            """,MealRecord.class).setParameter("userId",userId).setParameter("fromDate",today.minusDays(days-1))
            .setParameter("today",today).getResultList();
        return records.stream().map(r -> new RecentMeal(r.mealDate,r.mealType,
            entities.createQuery("select i.foodName from MealRecordItem i where i.mealRecordId = :id order by i.id",String.class)
                .setParameter("id",r.id).getResultList())).toList();
    }
    @Transactional(readOnly=true)
    public HistoryCount countFood(long userId, String foodName, Integer requestedDays) {
        if (foodName==null || foodName.isBlank() || foodName.trim().length()>200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"음식 이름을 확인해 주세요.");
        int days=requestedDays==null ? 14 : requestedDays;
        if (days<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"기간은 하루 이상이어야 합니다.");
        boolean limited=days>30;
        int usable=Math.min(days,30);
        LocalDate today=MealRecordPolicy.today(), from=today.minusDays(usable-1);
        Long count=entities.createQuery("""
            select count(distinct r.id) from MealRecord r, MealRecordItem i
            where r.userId = :userId and r.mealDate >= :fromDate and r.mealDate <= :today
              and i.mealRecordId = r.id and lower(trim(i.foodName)) = lower(:food)
            """,Long.class).setParameter("userId",userId).setParameter("fromDate",from)
            .setParameter("today",today).setParameter("food",foodName.trim()).getSingleResult();
        return new HistoryCount(foodName.trim(),from,today,count,limited);
    }
}
