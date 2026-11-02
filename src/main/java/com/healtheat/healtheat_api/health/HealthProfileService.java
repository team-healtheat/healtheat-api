package com.healtheat.healtheat_api.health;

import com.healtheat.healtheat_api.ai.HealthAnalysisClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HealthProfileService {
    private final JdbcTemplate db;
    private final HealthAnalysisClient analysis;
    private final jakarta.validation.Validator validator;
    public HealthProfileService(JdbcTemplate db, HealthAnalysisClient analysis, jakarta.validation.Validator validator) { this.db = db; this.analysis = analysis; this.validator = validator; }
    private record Master(Long id, String name, String type, String supported) {}
    private static final String MASTER_COLUMNS = "SELECT id, name, type, supported FROM health_factors ";
    private Master mapMaster(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Master(rs.getLong("id"), rs.getString("name"), rs.getString("type"),
                "SUPPORTED".equals(rs.getString("supported")) ? "SUPPORTED" : "UNSUPPORTED");
    }
    private Master uniqueMatch(String name, String type) {
        List<Master> matches = db.query(MASTER_COLUMNS + "WHERE lower(trim(name)) = lower(?) AND lower(trim(type)) = lower(?) ORDER BY id LIMIT 2",
            this::mapMaster, name.trim(), type.trim());
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private Master userMatch(long userId, String name, String type) {
        List<Master> matches = db.query("""
            SELECT DISTINCT f.id, f.name, f.type, f.supported FROM health_factors f
            JOIN user_health_factors u ON u.health_factor_id = f.id
            WHERE u.user_id = ? AND lower(trim(f.name)) = lower(?) AND lower(trim(f.type)) = lower(?)
            ORDER BY f.id LIMIT 2
            """, this::mapMaster, userId, name.trim(), type.trim());
        if (matches.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ambiguous health factor; specify factorId");
        return matches.isEmpty() ? null : matches.get(0);
    }
    private Master byId(long id) {
        List<Master> matches = db.query(MASTER_COLUMNS + "WHERE id = ?", this::mapMaster, id);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown health factor");
        return matches.get(0);
    }
    public HealthDtos.AnalysisResponse analyze(long userId, String text) {
        var result = analysis.analyze(text);
        if (result == null || !validator.validate(result).isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Health analysis response was invalid");
        List<HealthDtos.Candidate> candidates = new ArrayList<>();
        for (var factor : result.factors()) {
            Master master = userMatch(userId, factor.name(), factor.type());
            if (master == null) master = uniqueMatch(factor.name(), factor.type());
            candidates.add(new HealthDtos.Candidate(master == null ? null : master.id(), factor.name().trim(), factor.type().trim(),
                factor.reportedStatus().trim(), factor.originalText(), master == null ? "UNSUPPORTED" : master.supported()));
        }
        return new HealthDtos.AnalysisResponse(candidates);
    }
    @Transactional(readOnly = true)
    public HealthDtos.ProfileResponse get(long userId) {
        return new HealthDtos.ProfileResponse(db.query("""
            SELECT u.id, f.id AS factor_id, f.name, f.type, f.supported, u.reported_status, u.original_text, u.is_active
            FROM user_health_factors u JOIN health_factors f ON f.id = u.health_factor_id
            WHERE u.user_id = ? ORDER BY u.id
            """, (rs, row) -> new HealthDtos.ProfileFactor(rs.getLong("id"), rs.getLong("factor_id"), rs.getString("name"),
                rs.getString("type"), rs.getString("reported_status"), rs.getString("original_text"), rs.getBoolean("is_active"),
                "SUPPORTED".equals(rs.getString("supported")) ? "SUPPORTED" : "UNSUPPORTED"), userId));
    }
    @Transactional
    public HealthDtos.ProfileResponse confirm(long userId, HealthDtos.ConfirmRequest request) {
        // Serialize replacement operations for the same user without locking other users.
        if (db.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        db.update("UPDATE user_health_factors SET is_active = false, updated_at = now() WHERE user_id = ? AND is_active = true", userId);
        Set<Long> seen = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        for (var input : request.factors()) {
            String name = input.name().trim();
            String type = input.type().trim();
            String key = name.toLowerCase(java.util.Locale.ROOT) + "\u0000" + type.toLowerCase(java.util.Locale.ROOT);
            if (!seenNames.add(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate health factor");
            Master master = input.factorId() == null ? userMatch(userId, name, type) : byId(input.factorId());
            if (master == null && input.factorId() == null) master = uniqueMatch(name, type);
            if (master != null && (master.name() == null || master.type() == null || !master.name().equalsIgnoreCase(name) || !master.type().equalsIgnoreCase(type)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Health factor does not match master data");
            if (master == null) {
                Long id = db.queryForObject("INSERT INTO health_factors(code, name, type, supported) VALUES (?, ?, ?, 'UNSUPPORTED') RETURNING id",
                    Long.class, "CUSTOM_" + UUID.randomUUID(), name, type);
                master = byId(id);
            }
            if (!seen.add(master.id())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate health factor");
            List<Long> rows = db.queryForList("SELECT id FROM user_health_factors WHERE user_id = ? AND health_factor_id = ? ORDER BY id LIMIT 1",
                Long.class, userId, master.id());
            if (rows.isEmpty()) {
                db.update("INSERT INTO user_health_factors(user_id, health_factor_id, reported_status, original_text, is_active) VALUES (?, ?, ?, ?, ?)",
                    userId, master.id(), input.reportedStatus().trim(), input.originalText(), input.active());
            } else {
                db.update("UPDATE user_health_factors SET reported_status = ?, original_text = ?, is_active = ?, updated_at = now() WHERE id = ? AND user_id = ?",
                    input.reportedStatus().trim(), input.originalText(), input.active(), rows.get(0), userId);
            }
        }
        return get(userId);
    }
}
