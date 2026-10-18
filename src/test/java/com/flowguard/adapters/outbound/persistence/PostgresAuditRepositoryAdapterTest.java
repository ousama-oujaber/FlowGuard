package com.flowguard.adapters.outbound.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchCriteria;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchResult;
import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.value.MessageId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostgresAuditRepositoryAdapterTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private PostgresAuditRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        objectMapper = new ObjectMapper();
        adapter = new PostgresAuditRepositoryAdapter(jdbcTemplate, objectMapper);
    }

    @Test
    @DisplayName("save executes INSERT SQL with parameters")
    void testSave() {
        AuditEvent event = new AuditEvent(
                UUID.randomUUID(),
                "admin-user",
                "MANUAL_RETRY",
                MessageId.generate(),
                "Gateway recovered",
                Instant.now(),
                Map.of("key", "val")
        );

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        adapter.save(event);

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(contains("INSERT INTO audit_event"), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("id")).isEqualTo(event.id());
        assertThat(params.getValue("actorId")).isEqualTo("admin-user");
        assertThat(params.getValue("action")).isEqualTo("MANUAL_RETRY");
        assertThat(params.getValue("reason")).isEqualTo("Gateway recovered");
    }

    @Test
    @DisplayName("search builds parameterized SQL with all filter criteria and returns AuditSearchResult")
    @SuppressWarnings("unchecked")
    void testSearchWithAllFilters() {
        MessageId msgId = MessageId.generate();
        Instant from = Instant.parse("2026-10-18T00:00:00Z");
        Instant to = Instant.parse("2026-10-18T23:59:59Z");

        AuditSearchCriteria criteria = new AuditSearchCriteria(
                msgId,
                "ops-lead",
                "MANUAL_RETRY",
                from,
                to,
                1,
                15
        );

        when(jdbcTemplate.queryForObject(contains("COUNT(*)"), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(42L);

        AuditEvent sampleEvent = new AuditEvent(
                UUID.randomUUID(),
                "ops-lead",
                "MANUAL_RETRY",
                msgId,
                "Verified",
                Instant.now(),
                Map.of()
        );

        when(jdbcTemplate.query(contains("SELECT * FROM audit_event"), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(sampleEvent));

        AuditSearchResult result = adapter.search(criteria);

        assertThat(result.totalElements()).isEqualTo(42L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(15);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.events()).hasSize(1);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("message_id = :messageId");
        assertThat(sql).contains("actor_id = :actorId");
        assertThat(sql).contains("action = :action");
        assertThat(sql).contains("created_at >= :fromTime");
        assertThat(sql).contains("created_at <= :toTime");
        assertThat(sql).contains("ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");

        MapSqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("messageId")).isEqualTo(msgId.value());
        assertThat(params.getValue("actorId")).isEqualTo("ops-lead");
        assertThat(params.getValue("action")).isEqualTo("MANUAL_RETRY");
        assertThat(params.getValue("limit")).isEqualTo(15);
        assertThat(params.getValue("offset")).isEqualTo(15); // page 1 * 15 = 15
    }
}
