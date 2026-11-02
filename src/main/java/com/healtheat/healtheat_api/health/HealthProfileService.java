package com.healtheat.healtheat_api.health;

import com.healtheat.healtheat_api.ai.HealthAnalysisClient;
import com.healtheat.healtheat_api.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HealthProfileService {
    private final EntityManager entities;
    private final HealthAnalysisClient analysis;
    private final jakarta.validation.Validator validator;
    public HealthProfileService(EntityManager entities, HealthAnalysisClient analysis, jakarta.validation.Validator validator) { this.entities=entities; this.analysis=analysis; this.validator=validator; }
    private String supported(HealthFactor factor) { return "SUPPORTED".equals(factor.supported) ? "SUPPORTED" : "UNSUPPORTED"; }
    private HealthFactor uniqueMatch(String name, String type) {
        var matches=entities.createQuery("select f from HealthFactor f where lower(trim(f.name)) = lower(:name) and lower(trim(f.type)) = lower(:type) order by f.id", HealthFactor.class)
            .setParameter("name",name.trim()).setParameter("type",type.trim()).setMaxResults(2).getResultList();
        return matches.size()==1 ? matches.get(0) : null;
    }
    private HealthFactor userMatch(long userId, String name, String type) {
        var matches=entities.createQuery("select distinct f from UserHealthFactor u join u.factor f where u.userId = :userId and lower(trim(f.name)) = lower(:name) and lower(trim(f.type)) = lower(:type) order by f.id", HealthFactor.class)
            .setParameter("userId",userId).setParameter("name",name.trim()).setParameter("type",type.trim()).setMaxResults(2).getResultList();
        if (matches.size()>1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Ambiguous health factor; specify factorId");
        return matches.isEmpty() ? null : matches.get(0);
    }
    private HealthFactor byId(long id) {
        var factor=entities.find(HealthFactor.class,id);
        if (factor==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown health factor");
        return factor;
    }
    public HealthDtos.AnalysisResponse analyze(long userId, String text) {
        var result=analysis.analyze(text);
        if (result==null || !validator.validate(result).isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Health analysis response was invalid");
        List<HealthDtos.Candidate> candidates=new ArrayList<>();
        for (var factor: result.factors()) {
            HealthFactor master=userMatch(userId,factor.name(),factor.type());
            if (master==null) master=uniqueMatch(factor.name(),factor.type());
            candidates.add(new HealthDtos.Candidate(master==null ? null : master.id, factor.name().trim(), factor.type().trim(), factor.reportedStatus().trim(), factor.originalText(), master==null ? "UNSUPPORTED" : supported(master)));
        }
        return new HealthDtos.AnalysisResponse(candidates);
    }
    @Transactional(readOnly=true)
    public HealthDtos.ProfileResponse get(long userId) {
        var rows=entities.createQuery("select u from UserHealthFactor u join fetch u.factor where u.userId = :userId order by u.id",UserHealthFactor.class).setParameter("userId",userId).getResultList();
        return new HealthDtos.ProfileResponse(rows.stream().map(u -> new HealthDtos.ProfileFactor(u.id,u.factor.id,u.factor.name,u.factor.type,u.reportedStatus,u.originalText,Boolean.TRUE.equals(u.active),supported(u.factor))).toList());
    }
    @Transactional
    public HealthDtos.ProfileResponse confirm(long userId, HealthDtos.ConfirmRequest request) {
        if (entities.find(User.class,userId,LockModeType.PESSIMISTIC_WRITE)==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        entities.createQuery("update UserHealthFactor u set u.active = false, u.updatedAt = :now where u.userId = :userId and u.active = true")
            .setParameter("now",Instant.now()).setParameter("userId",userId).executeUpdate();
        Set<Long> seen=new HashSet<>(); Set<String> seenNames=new HashSet<>();
        for (var input: request.factors()) {
            String name=input.name().trim(); String type=input.type().trim();
            String key=name.toLowerCase(Locale.ROOT)+"\u0000"+type.toLowerCase(Locale.ROOT);
            if (!seenNames.add(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Duplicate health factor");
            HealthFactor master=input.factorId()==null ? userMatch(userId,name,type) : byId(input.factorId());
            if (master==null && input.factorId()==null) master=uniqueMatch(name,type);
            if (master!=null && (master.name==null || master.type==null || !master.name.equalsIgnoreCase(name) || !master.type.equalsIgnoreCase(type))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Health factor does not match master data");
            if (master==null) { master=new HealthFactor("CUSTOM_"+UUID.randomUUID(),name,type); entities.persist(master); }
            if (!seen.add(master.id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Duplicate health factor");
            var rows=entities.createQuery("select u from UserHealthFactor u where u.userId = :userId and u.factor.id = :factorId order by u.id",UserHealthFactor.class)
                .setParameter("userId",userId).setParameter("factorId",master.id).setMaxResults(1).getResultList();
            UserHealthFactor row;
            if (rows.isEmpty()) { row=new UserHealthFactor(userId,master); entities.persist(row); }
            else { row=rows.get(0); }
            row.updatedAt=Instant.now();
            row.reportedStatus=input.reportedStatus().trim(); row.originalText=input.originalText(); row.active=input.active();
        }
        entities.flush();
        return get(userId);
    }
}
