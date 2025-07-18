package no.nav.sf.github.metrics

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types

class Persistence(): IPersistence {

    /**
     * gets a connection and sets up tables if necessary
     */
    private fun setupConnection(statsTable: String, typeTable: String): Connection {
        val pg_db = System.getenv("NAIS_DATABASE_SF_GITHUB_METRICS_PGDB_DATABASE")
        val pg_user = System.getenv("NAIS_DATABASE_SF_GITHUB_METRICS_PGDB_USERNAME")
        val pg_pass = System.getenv("NAIS_DATABASE_SF_GITHUB_METRICS_PGDB_PASSWORD")
        val pg_host = System.getenv("NAIS_DATABASE_SF_GITHUB_METRICS_PGDB_HOST") ?: "localhost"
        val pg_port = System.getenv("NAIS_DATABASE_SF_GITHUB_METRICS_PGDB_PORT") ?: "5432"
        val url = System.getenv("NAIS_DATABASE_SF_GITHUB_METRICS_PGDB_JDBC_URL") ?: "jdbc:postgresql://$pg_host:$pg_port/$pg_db"
        logger.info("hey, let's expose stuff: $url")
        val conn: Connection = DriverManager.getConnection(url, pg_user, pg_pass)!!
        val tableSetupSql = """
            CREATE TABLE IF NOT EXISTS $statsTable (
                id SERIAL PRIMARY KEY,
                name VARCHAR(256) NOT NULL,
                tags VARCHAR(4096) NOT NULL,
                value DOUBLE PRECISION,
                UNIQUE (name, tags)
            );
            CREATE TABLE IF NOT EXISTS $typeTable (
                id SERIAL PRIMARY KEY,
                name VARCHAR(256) NOT NULL,
                type VARCHAR(256) NOT NULL,
                UNIQUE (name)
            );
        """.trimIndent()
        conn.createStatement().execute(tableSetupSql)
        return conn
    }

    /**
     * stores the # TYPE foo bar lines in the db
     */
    private fun upsertTypes(conn: Connection, typeTable: String, types: List<Type>): Int {
        var total = 0
        types.forEach {
            val blueprint = """
                INSERT INTO $typeTable (name, type)
                VALUES (?, ?)
                ON CONFLICT (name)
                DO UPDATE SET type = EXCLUDED.type
            """.trimIndent()
            val pstmt = conn.prepareStatement(blueprint)
            pstmt.setString(1, it.name)
            pstmt.setString(2, it.type)
            val rowsAffected = pstmt.executeUpdate()
            total++
        }
        return total
    }

    /**
     * gets types from the db
     */
    private fun getTypes(conn: Connection, typeTable: String, names: List<String>): ResultSet {
        val pstmt = conn.prepareStatement("""
            SELECT name, type FROM $typeTable WHERE name = ANY (?)
        """)
        val array = conn.createArrayOf("VARCHAR", names.toTypedArray())
        pstmt.setArray(1, array)
        return pstmt.executeQuery()
    }

    /**
     * maps names to types
     */
    private fun getTypeMap(rs: ResultSet): Map<String, String> {
        return generateSequence {
            if (rs.next()) rs.getString("name") to rs.getString("type") else null
        }.toMap()
    }

    /**
     * gets stats from the db
     */
    private fun getStats(conn: Connection, statsTable: String, names: List<String>): ResultSet {
        val pstmt = conn.prepareStatement("""
            SELECT name, tags, value FROM $statsTable WHERE name = ANY (?)
        """)
        val array = conn.createArrayOf("VARCHAR", names.toTypedArray())
        pstmt.setArray(1, array)
        return pstmt.executeQuery()
    }

    /**
     * gets a prometheus-formatted string of types
     */
    private fun formatTypeLines(rs: ResultSet): String {
        val lines = generateSequence {
            if (rs.next()) """# TYPE ${rs.getString("name")} ${rs.getString("type")}""" else null
        }.toList()
        return lines.joinToString("\n")
    }

    /**
     * gets a prometheus-formatted string of stats
     */
    private fun formatStatsLines(rs: ResultSet): String {
        val lines = generateSequence {
            if (rs.next()) """${rs.getString("name")}{${rs.getString("tags")}} ${rs.getDouble("value")}""" else null
        }.toList()
        return lines.joinToString("\n")
    }

    /**
     * separates lines from a message into their possible forms
     */
    private fun getTypesAndEntriesAndUnparseable(
        body: String,
        instance: String?
    ): Triple<List<Type>, List<Entry>, List<String>> {
        val lines = body.split("\n").map { it.trim() }
        val types = lines.filter { it.startsWith("# TYPE") }.map { Type.parseLine(it) }
        val entries = lines.filter { Entry.isValidLine(it) }.map { Entry.parseLine(instance, it) }
        val unparseable = lines.filter { !(
            it.startsWith("# TYPE") ||
            Entry.isValidLine(it) ||
            it == ""
        )}
        return Triple(types, entries, unparseable)
    }

    /**
     * updates stats in database, inserts when not present. counters are increased
     * by the given value, gauges are set to the given value.
     */
    private fun upsertStats(
        conn: Connection,
        typeTable: String,
        statsTable: String,
        entries: List<Entry>,
        names: List<String>
    ): Int {
        var total = 0
        val typeFromName = getTypeMap(getTypes(conn, typeTable, names))
        entries.forEach {
            val conflictAction = if(typeFromName[it.name] == "counter") {
                "DO UPDATE SET value = $statsTable.value + EXCLUDED.value"
            } else {
                "DO UPDATE SET value = EXCLUDED.value"
            }
            val pstmt = conn.prepareStatement("""
                INSERT INTO $statsTable (name, tags, value)
                VALUES (?, ?, ?)
                ON CONFLICT (name, tags)
                $conflictAction
            """)
            pstmt.setString(1, it.name)
            pstmt.setString(2, it.tagString)
            pstmt.setDouble(3, it.value)
            val rowsAffected = pstmt.executeUpdate()
            total++
        }
        return total
    }

    /**
     * updates database with given stats, returns updated stats each of which
     * contains an appropriate instance.
     */
    override fun updateStats(body: String, job: String, instance: String?): String {
        if ("""[a-zA-Z0-9_]+""".toRegex().matchEntire(job) == null) {
            throw JobNameException("Invalid job name: $job\nPlease only use alphanumeric characters.")
        }
        val statsTable = "stats_$job"
        val typeTable = "type_$job"
        val (types, entries, unparseable) = getTypesAndEntriesAndUnparseable(body, instance)
        unparseable.forEach {
            logger.warn("Encountered unparseable line:\n\t$it")
        }
        val names = entries.map { it.name }.distinct()
        val conn = setupConnection(statsTable, typeTable)
        val upsertedTypes = upsertTypes(conn, typeTable, types)
        val upsertedStats = upsertStats(conn, typeTable, statsTable, entries, names)
        logger.info("Upserted $upsertedTypes types and $upsertedStats stats with ${names.size} names")
        return formatTypeLines(getTypes(conn, typeTable, names)) + "\n" + formatStatsLines(getStats(conn, statsTable, names))
    }
}

class JobNameException(message: String): Exception(message)
