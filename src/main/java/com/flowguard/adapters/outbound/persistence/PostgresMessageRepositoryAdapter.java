package com.flowguard.adapters.outbound.persistence;

import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class PostgresMessageRepositoryAdapter implements MessageRepositoryPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresMessageRepositoryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_SQL = """
        INSERT INTO integration_message (
            id, client_id, message_type, source_system, destination_key,
            external_reference, correlation_id, payload, payload_size_bytes,
            payload_fingerprint, status, attempt_count, max_attempts,
            next_attempt_at, last_error_code, last_error_summary,
            lease_owner, lease_token, lease_until, created_at, updated_at,
            completed_at, version
        ) VALUES (
            :id, :clientId, :messageType, :sourceSystem, :destinationKey,
            :externalReference, :correlationId, CAST(:payload AS jsonb), :payloadSizeBytes,
            :payloadFingerprint, :status, :attemptCount, :maxAttempts,
            :nextAttemptAt, :lastErrorCode, :lastErrorSummary,
            :leaseOwner, :leaseToken, :leaseUntil, :createdAt, :updatedAt,
            :completedAt, :version
        )
        ON CONFLICT (id) DO UPDATE SET
            status = EXCLUDED.status,
            attempt_count = EXCLUDED.attempt_count,
            next_attempt_at = EXCLUDED.next_attempt_at,
            last_error_code = EXCLUDED.last_error_code,
            last_error_summary = EXCLUDED.last_error_summary,
            lease_owner = EXCLUDED.lease_owner,
            lease_token = EXCLUDED.lease_token,
            lease_until = EXCLUDED.lease_until,
            updated_at = EXCLUDED.updated_at,
            completed_at = EXCLUDED.completed_at,
            version = integration_message.version + 1
        """;

    @Override
    public void save(IntegrationMessage message) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", message.getId().value())
                .addValue("clientId", message.getClientId().value())
                .addValue("messageType", message.getMessageType().value())
                .addValue("sourceSystem", message.getSourceSystem())
                .addValue("destinationKey", message.getDestinationKey().value())
                .addValue("externalReference", message.getExternalReference())
                .addValue("correlationId", message.getCorrelationId().value())
                .addValue("payload", message.getPayload())
                .addValue("payloadSizeBytes", message.getPayloadSizeBytes())
                .addValue("payloadFingerprint", message.getPayloadFingerprint().value())
                .addValue("status", message.getStatus().name())
                .addValue("attemptCount", message.getAttemptCount())
                .addValue("maxAttempts", message.getMaxAttempts())
                .addValue("nextAttemptAt", message.getNextAttemptAt() != null ? Timestamp.from(message.getNextAttemptAt()) : null)
                .addValue("lastErrorCode", message.getLastErrorCode() != null ? message.getLastErrorCode().value() : null)
                .addValue("lastErrorSummary", message.getLastErrorSummary() != null ? message.getLastErrorSummary().value() : null)
                .addValue("leaseOwner", message.getLeaseOwner())
                .addValue("leaseToken", message.getLeaseToken() != null ? message.getLeaseToken().value() : null)
                .addValue("leaseUntil", message.getLeaseUntil() != null ? Timestamp.from(message.getLeaseUntil()) : null)
                .addValue("createdAt", Timestamp.from(message.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(message.getUpdatedAt()))
                .addValue("completedAt", message.getCompletedAt() != null ? Timestamp.from(message.getCompletedAt()) : null)
                .addValue("version", message.getVersion());

        jdbcTemplate.update(INSERT_SQL, params);
    }

    @Override
    public Optional<IntegrationMessage> findById(MessageId id) {
        String sql = "SELECT * FROM integration_message WHERE id = :id";
        try {
            IntegrationMessage message = jdbcTemplate.queryForObject(
                    sql,
                    new MapSqlParameterSource("id", id.value()),
                    new IntegrationMessageRowMapper()
            );
            return Optional.ofNullable(message);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<IntegrationMessage> findByIdAndClientId(MessageId id, ClientId clientId) {
        String sql = "SELECT * FROM integration_message WHERE id = :id AND client_id = :clientId";
        try {
            IntegrationMessage message = jdbcTemplate.queryForObject(
                    sql,
                    new MapSqlParameterSource()
                            .addValue("id", id.value())
                            .addValue("clientId", clientId.value()),
                    new IntegrationMessageRowMapper()
            );
            return Optional.ofNullable(message);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<IntegrationMessage> claimDueMessages(int batchSize, String workerId, LeaseToken token, Instant leaseUntil) {
        String selectSql = """
            SELECT id FROM integration_message
            WHERE status IN ('QUEUED', 'RETRY_SCHEDULED')
              AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """;

        List<UUID> ids = jdbcTemplate.queryForList(
                selectSql,
                new MapSqlParameterSource("batchSize", batchSize),
                UUID.class
        );

        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        String updateSql = """
            UPDATE integration_message
            SET status = 'PROCESSING',
                lease_owner = :workerId,
                lease_token = :token,
                lease_until = :leaseUntil,
                attempt_count = attempt_count + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (:ids)
            """;

        jdbcTemplate.update(
                updateSql,
                new MapSqlParameterSource()
                        .addValue("workerId", workerId)
                        .addValue("token", token.value())
                        .addValue("leaseUntil", Timestamp.from(leaseUntil))
                        .addValue("ids", ids)
        );

        String fetchSql = "SELECT * FROM integration_message WHERE id IN (:ids)";
        return jdbcTemplate.query(
                fetchSql,
                new MapSqlParameterSource("ids", ids),
                new IntegrationMessageRowMapper()
        );
    }

    @Override
    public boolean updateStatusWithLeaseFence(IntegrationMessage message, LeaseToken leaseToken) {
        String sql = """
            UPDATE integration_message
            SET status = :status,
                next_attempt_at = :nextAttemptAt,
                last_error_code = :lastErrorCode,
                last_error_summary = :lastErrorSummary,
                completed_at = :completedAt,
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND lease_token = :leaseToken
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", message.getId().value())
                .addValue("leaseToken", leaseToken != null ? leaseToken.value() : null)
                .addValue("status", message.getStatus().name())
                .addValue("nextAttemptAt", message.getNextAttemptAt() != null ? Timestamp.from(message.getNextAttemptAt()) : null)
                .addValue("lastErrorCode", message.getLastErrorCode() != null ? message.getLastErrorCode().value() : null)
                .addValue("lastErrorSummary", message.getLastErrorSummary() != null ? message.getLastErrorSummary().value() : null)
                .addValue("completedAt", message.getCompletedAt() != null ? Timestamp.from(message.getCompletedAt()) : null);

        int updated = jdbcTemplate.update(sql, params);
        return updated > 0;
    }

    @Override
    public long countByStatus(MessageStatus status) {
        String sql = "SELECT COUNT(*) FROM integration_message WHERE status = :status";
        Long count = jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource("status", status.name()),
                Long.class
        );
        return count != null ? count : 0L;
    }

    private static class IntegrationMessageRowMapper implements RowMapper<IntegrationMessage> {
        @Override
        public IntegrationMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = rs.getObject("id", UUID.class);
            String clientId = rs.getString("client_id");
            String messageType = rs.getString("message_type");
            String sourceSystem = rs.getString("source_system");
            String destinationKey = rs.getString("destination_key");
            String externalReference = rs.getString("external_reference");
            String correlationId = rs.getString("correlation_id");
            String payload = rs.getString("payload");
            int payloadSizeBytes = rs.getInt("payload_size_bytes");
            String payloadFingerprint = rs.getString("payload_fingerprint");
            String status = rs.getString("status");
            int attemptCount = rs.getInt("attempt_count");
            int maxAttempts = rs.getInt("max_attempts");
            Timestamp nextAttemptAt = rs.getTimestamp("next_attempt_at");
            String lastErrorCode = rs.getString("last_error_code");
            String lastErrorSummary = rs.getString("last_error_summary");
            String leaseOwner = rs.getString("lease_owner");
            UUID leaseToken = rs.getObject("lease_token", UUID.class);
            Timestamp leaseUntil = rs.getTimestamp("lease_until");
            Timestamp createdAt = rs.getTimestamp("created_at");
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            Timestamp completedAt = rs.getTimestamp("completed_at");
            long version = rs.getLong("version");

            return new IntegrationMessage(
                    new MessageId(id),
                    new ClientId(clientId),
                    new MessageType(messageType),
                    sourceSystem,
                    new DestinationKey(destinationKey),
                    new CorrelationId(correlationId),
                    externalReference,
                    payload,
                    payloadSizeBytes,
                    new PayloadFingerprint(payloadFingerprint),
                    MessageStatus.valueOf(status),
                    attemptCount,
                    maxAttempts,
                    nextAttemptAt != null ? nextAttemptAt.toInstant() : null,
                    lastErrorCode != null ? new FailureCode(lastErrorCode) : null,
                    lastErrorSummary != null ? new SafeErrorSummary(lastErrorSummary) : null,
                    leaseOwner,
                    leaseToken != null ? new LeaseToken(leaseToken) : null,
                    leaseUntil != null ? leaseUntil.toInstant() : null,
                    createdAt.toInstant(),
                    updatedAt != null ? updatedAt.toInstant() : createdAt.toInstant(),
                    completedAt != null ? completedAt.toInstant() : null,
                    version
            );
        }
    }
}
