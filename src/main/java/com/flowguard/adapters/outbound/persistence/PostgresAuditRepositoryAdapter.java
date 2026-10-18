package com.flowguard.adapters.outbound.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.core.application.port.out.AuditRepositoryPort;
import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.value.MessageId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class PostgresAuditRepositoryAdapter implements AuditRepositoryPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresAuditRepositoryAdapter(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private static final String INSERT_SQL = """
        INSERT INTO audit_event (
            id, actor_id, action, message_id, reason, created_at, metadata
        ) VALUES (
            :id, :actorId, :action, :messageId, :reason, :createdAt, CAST(:metadata AS jsonb)
        )
        """;

    @Override
    public void save(AuditEvent event) {
        String metadataJson = "{}";
        if (event.metadata() != null && !event.metadata().isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(event.metadata());
            } catch (JsonProcessingException e) {
                metadataJson = "{}";
            }
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("actorId", event.actorId())
                .addValue("action", event.action())
                .addValue("messageId", event.messageId() != null ? event.messageId().value() : null)
                .addValue("reason", event.reason())
                .addValue("createdAt", Timestamp.from(event.createdAt()))
                .addValue("metadata", metadataJson);

        jdbcTemplate.update(INSERT_SQL, params);
    }

    @Override
    public List<AuditEvent> findByMessageId(MessageId messageId) {
        String sql = "SELECT * FROM audit_event WHERE message_id = :messageId ORDER BY created_at DESC";
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("messageId", messageId.value()),
                new AuditEventRowMapper(objectMapper)
        );
    }

    private static class AuditEventRowMapper implements RowMapper<AuditEvent> {
        private final ObjectMapper objectMapper;

        public AuditEventRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public AuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = rs.getObject("id", UUID.class);
            String actorId = rs.getString("actor_id");
            String action = rs.getString("action");
            UUID messageId = rs.getObject("message_id", UUID.class);
            String reason = rs.getString("reason");
            Timestamp createdAt = rs.getTimestamp("created_at");
            String metadataJson = rs.getString("metadata");

            Map<String, String> metadata = Collections.emptyMap();
            if (metadataJson != null && !metadataJson.isBlank()) {
                try {
                    metadata = objectMapper.readValue(metadataJson, new TypeReference<>() {});
                } catch (Exception ignored) {
                }
            }

            return new AuditEvent(
                    id,
                    actorId,
                    action,
                    messageId != null ? new MessageId(messageId) : null,
                    reason,
                    createdAt.toInstant(),
                    metadata
            );
        }
    }
}
