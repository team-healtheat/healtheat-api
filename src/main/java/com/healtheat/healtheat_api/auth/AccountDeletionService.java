package com.healtheat.healtheat_api.auth;

import com.healtheat.healtheat_api.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountDeletionService {
    private final JdbcTemplate db; private final UserRepository users; private final PasswordEncoder passwords;
    public AccountDeletionService(JdbcTemplate db,UserRepository users,PasswordEncoder passwords) { this.db=db; this.users=users; this.passwords=passwords; }
    @Transactional
    public void delete(long userId,String password) {
        var user=users.findById(userId).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required"));
        if(!passwords.matches(password,user.getPasswordHash())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid password");
        db.queryForObject("select id from users where id=? for update",Long.class,userId);
        var factors=db.queryForList("select health_factor_id from user_health_factors where user_id=?",Long.class,userId);
        db.update("delete from meal_record_items where meal_record_id in (select id from meal_records where user_id=?)",userId);
        db.update("delete from meal_records where user_id=?",userId);
        String recipes="select r.id from recipes r join meal_items i on i.id=r.meal_item_id join meal_recommendations m on m.id=i.meal_recommendation_id join meal_plans p on p.id=m.meal_plan_id where p.user_id=?";
        db.update("delete from recipe_ingredients where recipe_id in ("+recipes+")",userId);
        db.update("delete from recipe_steps where recipe_id in ("+recipes+")",userId);
        db.update("delete from recipes where id in ("+recipes+")",userId);
        db.update("delete from meal_items where meal_recommendation_id in (select r.id from meal_recommendations r join meal_plans p on p.id=r.meal_plan_id where p.user_id=?)",userId);
        db.update("delete from meal_recommendations where meal_plan_id in (select id from meal_plans where user_id=?)",userId);
        db.update("delete from meal_plans where user_id=?",userId);
        db.update("delete from chat_messages where chat_session_id in (select id from chat_sessions where user_id=?)",userId);
        db.update("delete from chat_sessions where user_id=?",userId);
        db.update("delete from pending_actions where user_id=?",userId);
        db.update("delete from user_health_factors where user_id=?",userId);
        for(Long factorId:factors) db.update("delete from health_factors where id=? and left(code,7)='CUSTOM_' and not exists (select 1 from user_health_factors where health_factor_id=?)",factorId,factorId);
        db.update("delete from user_allergies where user_id=?",userId);
        db.update("delete from user_preferences where user_id=?",userId);
        db.update("delete from refresh_sessions where user_id=?",userId);
        db.update("delete from users where id=?",userId);
    }
}
