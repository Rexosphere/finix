package org.finix.compliance.adapter.out.persistence

import org.finix.compliance.application.port.CaseRepository
import org.finix.compliance.domain.Case
import org.finix.compliance.domain.CaseSeverity
import org.finix.compliance.domain.CaseStatus
import org.finix.compliance.domain.CaseType
import org.finix.kernel.domain.DomainError
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/** JDBC adapter over `compliance_case` (V1__compliance.sql). */
@Repository
class JdbcCaseRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : CaseRepository {

    /**
     * Optimistic locking matters here even though cases are low-traffic: two investigators
     * closing the same case from different consoles must not silently overwrite each other's
     * notes, which are evidence.
     */
    override fun save(case: Case): Case {
        val updated = jdbc.update(UPDATE, caseParams(case).addValue("nextVersion", case.version + 1))
        if (updated > 0) {
            return case.withVersion(case.version + 1)
        }
        val inserted = jdbc.update(INSERT, caseParams(case))
        if (inserted == 0) {
            DomainError.ConcurrentModification("Case", case.id.toString()).raise()
        }
        return case
    }

    override fun findById(id: UUID): Case? =
        jdbc.query(SELECT_BY_ID, MapSqlParameterSource("id", id)) { rs, _ -> mapRow(rs) }.firstOrNull()

    override fun findAll(): List<Case> =
        jdbc.query(SELECT_ALL, MapSqlParameterSource()) { rs, _ -> mapRow(rs) }

    private fun caseParams(case: Case) = MapSqlParameterSource()
        .addValue("id", case.id)
        .addValue("type", case.type.name)
        .addValue("subjectRef", case.subjectRef)
        .addValue("status", case.status.name)
        .addValue("severity", case.severity.name)
        .addValue("notes", case.notes)
        .addValue("openedAt", Timestamp.from(case.openedAt))
        .addValue("updatedAt", Timestamp.from(case.updatedAt))
        .addValue("closedAt", case.closedAt?.let { Timestamp.from(it) })
        .addValue("version", case.version)

    private fun mapRow(rs: ResultSet) = Case(
        id = rs.getObject("id", UUID::class.java),
        type = CaseType.valueOf(rs.getString("type")),
        subjectRef = rs.getString("subject_ref"),
        status = CaseStatus.valueOf(rs.getString("status")),
        severity = CaseSeverity.valueOf(rs.getString("severity")),
        notes = rs.getString("notes"),
        openedAt = rs.getTimestamp("opened_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
        closedAt = rs.getTimestamp("closed_at")?.toInstant(),
        version = rs.getLong("version"),
    )

    private companion object {
        const val COLUMNS = """
            id, type, subject_ref, status, severity, notes, opened_at, updated_at, closed_at, version
        """

        const val SELECT_BY_ID = "SELECT $COLUMNS FROM compliance_case WHERE id = :id"
        const val SELECT_ALL = "SELECT $COLUMNS FROM compliance_case ORDER BY opened_at DESC"

        const val INSERT = """
            INSERT INTO compliance_case (
                id, type, subject_ref, status, severity, notes, opened_at, updated_at, closed_at, version
            ) VALUES (
                :id, :type, :subjectRef, :status, :severity, :notes, :openedAt, :updatedAt, :closedAt, :version
            )
            ON CONFLICT (id) DO NOTHING
        """

        const val UPDATE = """
            UPDATE compliance_case SET
                status     = :status,
                severity   = :severity,
                notes      = :notes,
                updated_at = :updatedAt,
                closed_at  = :closedAt,
                version    = :nextVersion
            WHERE id = :id AND version = :version
        """

        fun Case.withVersion(version: Long) = Case(
            id = id,
            type = type,
            subjectRef = subjectRef,
            status = status,
            severity = severity,
            notes = notes,
            openedAt = openedAt,
            updatedAt = updatedAt,
            closedAt = closedAt,
            version = version,
        )
    }
}
