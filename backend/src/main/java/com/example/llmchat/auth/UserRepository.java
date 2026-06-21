package com.example.llmchat.auth;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private static final RowMapper<UserRecord> ROW_MAPPER = (rs, rowNum) -> new UserRecord(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            Instant.parse(rs.getString("created_at")));

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserRecord create(String username, String passwordHash) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
                id, username, passwordHash, now);
        return new UserRecord(id, username, passwordHash, Instant.parse(now));
    }

    public UserRecord createWithId(String id, String username, String passwordHash) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
                id, username, passwordHash, now);
        return new UserRecord(id, username, passwordHash, Instant.parse(now));
    }

    public Optional<UserRecord> findByUsername(String username) {
        try {
            UserRecord user = jdbcTemplate.queryForObject(
                    "SELECT id, username, password_hash, created_at FROM users WHERE username = ?",
                    ROW_MAPPER,
                    username);
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<UserRecord> findById(String id) {
        try {
            UserRecord user = jdbcTemplate.queryForObject(
                    "SELECT id, username, password_hash, created_at FROM users WHERE id = ?",
                    ROW_MAPPER,
                    id);
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class,
                username);
        return count != null && count > 0;
    }
}
