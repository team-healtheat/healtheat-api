package com.healtheat.healtheat_api.mealrecord;

import com.healtheat.healtheat_api.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class MealRecordService {
    private static final Set<String> MEALS=Set.of("BREAKFAST","LUNCH","DINNER");
    private static final Set<String> SOURCES=Set.of("HOME_COOKED","DINING_OUT");
    private final EntityManager entities;
    MealRecordService(EntityManager entities) { this.entities=entities; }

    @Transactional
    public MealRecordDtos.Record createDirect(long userId, MealRecordDtos.DirectRequest request) {
        lockUser(userId);
        if (!MEALS.contains(request.mealType())) throw badRequest("Unknown meal type");
        if (request.sourceType()!=null && !SOURCES.contains(request.sourceType())) throw badRequest("Unknown source type");
        var record=new MealRecord(); record.userId=userId; record.mealDate=request.mealDate(); record.mealType=request.mealType();
        record.sourceType=request.sourceType(); record.fromRecommendation=false; entities.persist(record);
        for (var food: request.items()) saveItem(record.id,food.foodName().trim(),food.servingDescription()==null ? null : food.servingDescription().trim());
        entities.flush(); entities.refresh(record);
        return response(record);
    }
    @Transactional
    public MealRecordDtos.Record createFromRecommendation(long userId, long recommendationId) {
        lockUser(userId);
        var recommendation=entities.createQuery("""
            select p.planDate, r.mealType, r.sourceType from MealRecommendation r, MealPlan p
            where r.id = :recommendationId and r.mealPlanId = p.id and p.userId = :userId
            """,Object[].class).setParameter("recommendationId",recommendationId).setParameter("userId",userId).getResultList();
        if (recommendation.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Recommendation not found");
        var existing=entities.createQuery("select r from MealRecord r where r.userId = :userId and r.recommendationId = :recommendationId",MealRecord.class)
            .setParameter("userId",userId).setParameter("recommendationId",recommendationId).getResultList();
        if (!existing.isEmpty()) return response(existing.get(0));
        var names=entities.createQuery("select i.foodName from MealItem i where i.recommendationId = :id order by i.id",String.class)
            .setParameter("id",recommendationId).getResultList();
        if (names.isEmpty() || names.stream().anyMatch(name -> name==null || name.isBlank())) throw badRequest("Recommendation has no usable foods");
        Object[] source=recommendation.get(0);
        var record=new MealRecord(); record.userId=userId; record.mealDate=(LocalDate)source[0]; record.mealType=(String)source[1];
        record.sourceType=(String)source[2]; record.fromRecommendation=true; record.recommendationId=recommendationId; entities.persist(record);
        for (String name: names) saveItem(record.id,name,null);
        entities.flush(); entities.refresh(record);
        return response(record);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<MealRecordDtos.Record> list(long userId, LocalDate from, LocalDate to) {
        if (from!=null && to!=null && from.isAfter(to)) throw badRequest("from must not be after to");
        String jpql="select r from MealRecord r where r.userId = :userId";
        if (from!=null) jpql+=" and r.mealDate >= :fromDate";
        if (to!=null) jpql+=" and r.mealDate <= :toDate";
        var query=entities.createQuery(jpql+" order by r.mealDate desc, r.id desc",MealRecord.class).setParameter("userId",userId);
        if (from!=null) query.setParameter("fromDate",from);
        if (to!=null) query.setParameter("toDate",to);
        return query.getResultList().stream().map(this::response).toList();
    }
    private void lockUser(long userId) {
        if (entities.find(User.class,userId,LockModeType.PESSIMISTIC_WRITE)==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
    }
    private void saveItem(long recordId,String name,String serving) {
        var item=new MealRecordItem(); item.mealRecordId=recordId; item.foodName=name; item.servingDescription=serving; entities.persist(item);
    }
    private MealRecordDtos.Record response(MealRecord record) {
        var items=entities.createQuery("select i from MealRecordItem i where i.mealRecordId = :id order by i.id",MealRecordItem.class)
            .setParameter("id",record.id).getResultList().stream().map(i -> new MealRecordDtos.Food(i.id,i.foodName,i.servingDescription)).toList();
        return new MealRecordDtos.Record(record.id,record.mealDate,record.mealType,record.sourceType,Boolean.TRUE.equals(record.fromRecommendation),record.recommendationId,record.createdAt,items);
    }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
