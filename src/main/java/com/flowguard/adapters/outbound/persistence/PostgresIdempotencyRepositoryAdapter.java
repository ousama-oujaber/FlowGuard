package com.flowguard.adapters.outbound.persistence;

import com.flowguard.core.application.port.out.IdempotencyRepositoryPort;
import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.MessageId;
import com.flowguard.core.domain.value.PayloadFingerprint;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresIdempotencyRepositoryAdapter implements IdempotencyRepositoryPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresIdempotencyRepositoryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_SQL = """
        INSERT INTO idempotency_record (
            client_id, key_digest, request_fingerprint, message_id,
            created_at, expires_at
        ) VALUES (
            :clientId, :keyDigest, :requestFingerprint, :messageId,
            :createdAt, :expiresAt
        )
        ON CONFLICT (client_id, key_digest) DO NOTHING
        """;

    @Override
    public void save(IdempotencyEntry entry) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", entry.clientId().value())
                .addValue("keyDigest", entry.keyDigest())
                .addValue("requestFingerprint", entry.requestFingerprint().value())
                .addValue("messageId", entry.messageId().value())
                .addValue("createdAt", Timestamp.from(entry.createdAt()))
                .addValue("expiresAt", Timestamp.from(entry.expiresAt()));

        jdbcTemplate.update(INSERT_SQL, params);
    }

    @Override
    public Optional<IdempotencyEntry> find(ClientId clientId, String keyDigest) {
        String sql = "SELECT * FROM idempotency_record WHERE client_id = :clientId AND key_digest = :keyDigest";
        try {
            IdempotencyEntry entry = jdbcTemplate.queryForObject(
                    sql,
                    new MapSqlParameterSource()
                            .addValue("clientId", clientId.value())
                            .addValue("keyDigest", keyDigest),
                    new IdempotencyEntryRowMapper()
            );
            return Optional.ofNullable(entry);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static class IdempotencyEntryRowMapper implements RowMapper<IdempotencyEntry> {
        @Override
        public IdempotencyEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            String clientId = rs.getString("client_id");
            String keyDigest = rs.getString("key_digest");
            String requestFingerprint = rs.getString("request_fingerprint");
            UUID messageId = rs.getObject("message_id", UUID.class);
            Timestamp createdAt = rs.getTimestamp("created_at");
            Timestamp expiresAt = rs.getTimestamp("expires_at");

            return new IdempotencyEntry(
                    new ClientId(clientId),
                    keyDigest,
                    new PayloadFingerprint(requestFingerprint),
                    new MessageId(messageId),
                    createdAt.toInstant(),
                    expiresAt.toInstant()
            );
        }
    }
}
