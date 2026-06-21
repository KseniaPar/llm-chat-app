package com.example.llmchat.personalization;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class UserProfileRepository {

    private static final RowMapper<UserProfile> ROW_MAPPER = (rs, rowNum) -> new UserProfile(
            rs.getString("user_id"),
            rs.getString("display_name"),
            rs.getString("response_style"),
            rs.getString("response_format"),
            rs.getString("constraints"),
            Instant.parse(rs.getString("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public UserProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserProfile> findByUserId(String userId) {
        try {
            UserProfile profile = jdbcTemplate.queryForObject(
                    """
                            SELECT user_id, display_name, response_style, response_format, constraints, updated_at
                            FROM user_profiles WHERE user_id = ?
                            """,
                    ROW_MAPPER,
                    userId);
            return Optional.ofNullable(profile);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public UserProfile upsert(UserProfile profile) {
        String now = Instant.now().toString();
        int updated = jdbcTemplate.update(
                """
                        UPDATE user_profiles
                        SET display_name = ?, response_style = ?, response_format = ?, constraints = ?, updated_at = ?
                        WHERE user_id = ?
                        """,
                profile.displayName(),
                profile.responseStyle(),
                profile.responseFormat(),
                profile.constraints(),
                now,
                profile.userId());
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            INSERT INTO user_profiles
                            (user_id, display_name, response_style, response_format, constraints, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    profile.userId(),
                    profile.displayName(),
                    profile.responseStyle(),
                    profile.responseFormat(),
                    profile.constraints(),
                    now);
        }
        return findByUserId(profile.userId()).orElse(profile);
    }
}
