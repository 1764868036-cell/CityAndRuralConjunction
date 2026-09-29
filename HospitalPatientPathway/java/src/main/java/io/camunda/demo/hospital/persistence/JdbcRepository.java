package io.camunda.demo.hospital.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The JDBC plumbing every pathway repository shares.
 *
 * <p>Each aggregate repository owns the SQL of its own table; this class holds only the statements
 * that are the same for every table: reading a row by primary key, listing the table and counting
 * it. The row mapper is declared here once and reused by every query of the subclass, so a
 * repository spells out its columns exactly once and never returns rows to its callers.
 *
 * <p>The class is package-private on purpose. The repositories in
 * {@code io.camunda.demo.hospital.persistence} are the only JDBC code of the application and the
 * facade {@link HospitalDatabase} is the only entry point, so neither this base class nor the JDBC
 * types it uses can leak into worker code.
 *
 * <p>The repositories are plain {@code @Component}s rather than {@code @Repository}s on purpose:
 * {@code JdbcClient} - like {@code JdbcTemplate} underneath it - already translates a
 * {@code SQLException} into Spring's {@code DataAccessException} hierarchy, so the exception
 * translation a {@code @Repository} would add is not needed. It would also cost correctness here:
 * a {@code @Repository} without an interface is proxied by CGLIB, and CGLIB cannot intercept the
 * final methods of this base class - they would run on the uninitialized proxy instance instead of
 * the repository and fail with a null {@code JdbcClient}.
 *
 * <p>The repository constructors are package-private for the same reason: the {@code JdbcClient} is
 * the injection seam of this package. Spring still creates the beans from that single constructor,
 * but no caller outside the package can build a repository around a datasource of its own.
 *
 * @param <T> the aggregate this repository stores
 * @param <ID> the type of the aggregate's primary key
 */
abstract class JdbcRepository<T, ID> {

    private final JdbcClient jdbc;
    private final String table;
    private final String idColumn;
    private final RowMapper<T> rows = this::mapRow;

    /**
     * @param jdbc     the client of the H2 datasource
     * @param table    name of the table this repository owns
     * @param idColumn name of that table's primary key column
     */
    protected JdbcRepository(JdbcClient jdbc, String table, String idColumn) {
        this.jdbc = jdbc;
        this.table = table;
        this.idColumn = idColumn;
    }

    /** The JDBC client for the statements the subclass owns. */
    protected final JdbcClient jdbc() {
        return jdbc;
    }

    /** The row mapper of the table, for the queries the subclass adds. */
    protected final RowMapper<T> rows() {
        return rows;
    }

    /** Maps one row of the table; the columns are addressed by name, the aggregates stay free of them. */
    protected abstract T mapRow(ResultSet row, int index) throws SQLException;

    /** The row with this primary key, empty when the pathway has not produced it yet. */
    public final Optional<T> findById(ID id) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE " + idColumn + " = ?")
                .param(id)
                .query(rows)
                .optional();
    }

    /** Every row of the table, ordered by primary key so a listing stays reproducible. */
    public final List<T> findAll() {
        return jdbc.sql("SELECT * FROM " + table + " ORDER BY " + idColumn).query(rows).list();
    }

    /** Number of rows the table holds. */
    public final long count() {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    /** Whether the table holds this primary key. */
    public final boolean existsById(ID id) {
        return findById(id).isPresent();
    }
}
