package com.flowguard.adapters.outbound.persistence;

import com.flowguard.core.application.port.out.AttemptRepositoryPort;
import com.flowguard.core.domain.model.AttemptStatus;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.FailureCode;
import com.flowguard.core.domain.value.MessageId;
import com.flowguard.core.domain.value.SafeErrorSummary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class PostgresAttemptRepositoryAdapter implements AttemptRepositoryPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresAttemptRepositoryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_OR_UPDATE_SQL = """
        INSERT INTO processing_attempt (
            id, message_id, attempt_number, status, worker_id, started_at,
            completed_at, duration_ms, error_code, error_category, retryable,
            target_status_code, trace_id, safe_summary
        ) VALUES (
            :id, :messageId, :attemptNumber, :status, :workerId, :startedAt,
            :completedAt, :durationMs, :errorCode, :errorCategory, :retryable,
            :targetStatusCode, :traceId, :safeSummary
        )
        ON CONFLICT (message_id, attempt_number) DO UPDATE SET
            status = EXCLUDED.status,
            completed_at = EXCLUDED.completed_at,
            duration_ms = EXCLUDED.duration_ms,
            error_code = EXCLUDED.error_code,
            error_category = EXCLUDED.error_category,
            retryable = EXCLUDED.retryable,
            target_status_code = EXCLUDED.target_status_code,
            safe_summary = EXCLUDED.safe_summary
        """;

    @Override
    public void save(ProcessingAttempt attempt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", attempt.getId())
                .addValue("messageId", attempt.getMessageId().value())
                .addValue("attemptNumber", attempt.getAttemptNumber())
                .addValue("status", attempt.getStatus().name())
                .addValue("workerId", attempt.getWorkerId())
                .addValue("startedAt", Timestamp.from(attempt.getStartedAt()))
                .addValue("completedAt", attempt.getCompletedAt() != null ? Timestamp.from(attempt.getCompletedAt()) : null)
                .addValue("durationMs", attempt.getDurationMs())
                .addValue("errorCode", attempt.getErrorCode() != null ? attempt.getErrorCode().value() : null)
                .addValue("errorCategory", attempt.getErrorCategory() != null ? attempt.getErrorCategory().name() : null)
                .addValue("retryable", attempt.getRetryable())
                .addValue("targetStatusCode", attempt.getTargetStatusCode())
                .addValue("traceId", attempt.getTraceId())
                .addValue("safeSummary", attempt.getSafeSummary() != null ? attempt.getSafeSummary().value() : null);

        jdbcTemplate.update(INSERT_OR_UPDATE_SQL, params);
    }

    @Override
    public List<ProcessingAttempt> findByMessageId(MessageId messageId) {
        String sql = "SELECT * FROM processing_attempt WHERE message_id = :messageId ORDER BY attempt_number ASC";
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("messageId", messageId.value()),
                new ProcessingAttemptRowMapper()
        );
    }

    private static class ProcessingAttemptRowMapper implements RowMapper<ProcessingAttempt> {
        @Override
        public ProcessingAttempt mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = rs.getObject("id", UUID.class);
            UUID messageId = rs.getObject("message_id", UUID.class);
            int attemptNumber = rs.getInt("attempt_number");
            String status = rs.getString("status");
            String workerId = rs.getString("worker_id");
            Timestamp startedAt = rs.getTimestamp("started_at");
            Timestamp completedAt = rs.getTimestamp("completed_at");
            Long durationMs = rs.getObject("duration_ms", Long.class);
            String errorCode = rs.getString("error_code");
            String errorCategory = rs.getString("error_category");
            Boolean retryable = rs.getObject("retryable", Boolean.class);
            Integer targetStatusCode = rs.getObject("target_status_code", Integer.class);
            String traceId = rs.getString("trace_id");
            String safeSummary = rs.getString("safe_summary");

            return new ProcessingAttempt(
                    id,
                    new MessageId(messageId),
                    attemptNumber,
                    AttemptStatus.valueOf(status),
                    workerId,
                    startedAt.toInstant(),
                    completedAt != null ? completedAt.toInstant() : null,
                    durationMs,
                    errorCode != null ? new FailureCode(errorCode) : null,
                    errorCategory != null ? ErrorCategory.valueOf(errorCategory) : null,
                    retryable,
                    targetStatusCode,
                    traceId,
                    safeSummary != null ? new SafeErrorSummary(safeSummary) : null
            );
        }
    }
}
