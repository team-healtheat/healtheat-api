package com.healtheat.healtheat_api.auth;

import com.healtheat.healtheat_api.user.User;
import com.healtheat.healtheat_api.user.UserRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    public AuthService(UserRepository users, PasswordEncoder passwords) { this.users = users; this.passwords = passwords; }

    @Transactional
    public User signup(AuthDtos.SignupRequest request) {
        if (!request.password().equals(request.passwordConfirm())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match");
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        String email = request.email().trim();
        if (users.existsByEmail(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        try { return users.saveAndFlush(new User(email, passwords.encode(request.password()))); }
        catch (DataIntegrityViolationException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered"); }
    }
    @Transactional(readOnly = true)
    public User authenticate(AuthDtos.LoginRequest request) {
        User user = users.findByEmail(request.email().trim()).orElse(null);
        if (user == null || request.password().getBytes(StandardCharsets.UTF_8).length > 72 || !passwords.matches(request.password(), user.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        return user;
    }
    @Transactional(readOnly = true)
    public User getUser(long id) { return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")); }
}
