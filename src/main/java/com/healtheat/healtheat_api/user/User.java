package com.healtheat.healtheat_api.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(nullable = false) private String role;
    private Integer age;
    private String gender;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false) private Instant updatedAt;

    protected User() {}
    public User(String email, String passwordHash) { this.email = email; this.passwordHash = passwordHash; this.role = "USER"; }
    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public Integer getAge() { return age; }
    public String getGender() { return gender; }
    public void updateBasicProfile(Integer age, String gender) { this.age = age; this.gender = gender; }
}
