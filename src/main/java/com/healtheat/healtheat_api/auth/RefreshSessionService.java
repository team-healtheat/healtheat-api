package com.healtheat.healtheat_api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefreshSessionService {
    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();
    public RefreshSessionService(JdbcTemplate db) { this.db = db; }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    @Transactional
    public String issue(long userId) {
        db.update("delete from refresh_sessions where expires_at <= now()");
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("insert into refresh_sessions(user_id,token_hash,expires_at) values (?,?,?)",userId,hash(token),java.sql.Timestamp.from(Instant.now().plus(30,ChronoUnit.DAYS)));
        return token;
    }
    public record Rotation(long userId,String token) {}
    @Transactional
    public Rotation rotate(String token) {
        if (token==null || token.isBlank() || token.length()>256) throw unauthorized();
        var rows=db.query("select id,user_id,expires_at from refresh_sessions where token_hash=? for update",(rs,n)->new Object[]{rs.getLong(1),rs.getLong(2),rs.getTimestamp(3).toInstant()},hash(token));
        if(rows.isEmpty() || !((Instant)rows.get(0)[2]).isAfter(Instant.now())) throw unauthorized();
        long id=(long)rows.get(0)[0], userId=(long)rows.get(0)[1];
        db.update("delete from refresh_sessions where id=?",id);
        return new Rotation(userId,issue(userId));
    }
    @Transactional public void revoke(String token) { if (token!=null && !token.isBlank() && token.length()<=256) db.update("delete from refresh_sessions where token_hash=?",hash(token)); }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Login required"); }
}
