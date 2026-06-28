package com.example.mcp.study;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StudyReferenceTools {

    private static final Logger log = LoggerFactory.getLogger(StudyReferenceTools.class);

    private final JdbcTemplate jdbcTemplate;

    public StudyReferenceTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = """
            Search the educational study reference by keyword in topic, summary, or exam hints.
            Use when the student asks about a religious topic, exam preparation, or definitions.""")
    public Map<String, Object> searchTopic(
            @ToolParam(description = "Search query, e.g. 'четыре благородные истины'") String query,
            @ToolParam(description = "Optional subject filter, e.g. 'буддизм'", required = false) String subject) {
        if (query == null || query.isBlank()) {
            return Map.of("matches", List.of(), "message", "query is required");
        }

        String normalizedQuery = McpEncodingFix.normalize(query);
        String normalizedSubject = subject != null ? McpEncodingFix.normalize(subject) : null;

        List<Map<String, Object>> matches = search(normalizedQuery, normalizedSubject);
        if (matches.isEmpty() && normalizedSubject != null && !normalizedSubject.isBlank()) {
            matches = search(normalizedQuery, null);
        }
        log.info("searchTopic query='{}' subject='{}' -> {} match(es)",
                normalizedQuery, normalizedSubject != null ? normalizedSubject : "", matches.size());
        return Map.of(
                "query", normalizedQuery,
                "subjectFilter", normalizedSubject != null ? normalizedSubject : "",
                "matchCount", matches.size(),
                "matches", matches);
    }

    @Tool(description = """
            Get an exam preparation outline for a subject — list of topics with exam hints.""")
    public Map<String, Object> getExamOutline(
            @ToolParam(description = "Subject name, e.g. 'буддизм' or 'индуизм'") String subject) {
        if (subject == null || subject.isBlank()) {
            return Map.of("topics", List.of(), "message", "subject is required");
        }

        String normalizedSubject = McpEncodingFix.normalize(subject);
        String subjectPattern = "%" + normalizedSubject.trim().toLowerCase() + "%";
        List<Map<String, Object>> topics = jdbcTemplate.query(
                """
                        SELECT topic, exam_hints AS examHints
                        FROM study_topics
                        WHERE LOWER(subject) LIKE ?
                        ORDER BY topic
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("topic", rs.getString("topic"));
                    row.put("examHints", rs.getString("examHints"));
                    return row;
                },
                subjectPattern);

        log.info("getExamOutline subject='{}' -> {} topic(s)", normalizedSubject, topics.size());

        return Map.of(
                "subject", normalizedSubject,
                "topicCount", topics.size(),
                "topics", topics);
    }

    private List<Map<String, Object>> search(String query, String subject) {
        Set<String> patterns = new LinkedHashSet<>();
        String normalized = query.trim().toLowerCase();
        patterns.add("%" + normalized + "%");
        for (String word : normalized.split("\\s+")) {
            if (word.length() >= 3) {
                patterns.add("%" + word + "%");
            }
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String pattern : patterns) {
            for (Map<String, Object> row : queryOnce(pattern, subject)) {
                String key = row.get("subject") + "|" + row.get("topic");
                if (seen.add(key)) {
                    merged.add(row);
                }
            }
            if (!merged.isEmpty()) {
                break;
            }
        }
        return merged.size() > 10 ? merged.subList(0, 10) : merged;
    }

    private List<Map<String, Object>> queryOnce(String pattern, String subject) {
        if (subject != null && !subject.isBlank()) {
            String subjectPattern = "%" + subject.trim().toLowerCase() + "%";
            return jdbcTemplate.query(
                    """
                            SELECT subject, topic, summary, exam_hints AS examHints
                            FROM study_topics
                            WHERE LOWER(subject) LIKE ?
                              AND (LOWER(topic) LIKE ? OR LOWER(summary) LIKE ? OR LOWER(exam_hints) LIKE ?)
                            ORDER BY topic
                            LIMIT 10
                            """,
                    (rs, rowNum) -> rowMap(rs),
                    subjectPattern,
                    pattern,
                    pattern,
                    pattern);
        }
        return jdbcTemplate.query(
                """
                        SELECT subject, topic, summary, exam_hints AS examHints
                        FROM study_topics
                        WHERE LOWER(topic) LIKE ? OR LOWER(summary) LIKE ? OR LOWER(exam_hints) LIKE ?
                        ORDER BY subject, topic
                        LIMIT 10
                        """,
                (rs, rowNum) -> rowMap(rs),
                pattern,
                pattern,
                pattern);
    }

    private Map<String, Object> rowMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subject", rs.getString("subject"));
        row.put("topic", rs.getString("topic"));
        row.put("summary", rs.getString("summary"));
        row.put("examHints", rs.getString("examHints"));
        return row;
    }
}
