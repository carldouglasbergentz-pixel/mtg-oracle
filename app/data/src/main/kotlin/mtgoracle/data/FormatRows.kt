package mtgoracle.data

import mtgoracle.core.lookup.CustomFormat
import mtgoracle.core.lookup.FormatCatalog
import java.sql.Connection

/** The formats `custom_formats` defines, on an open connection: the Lookup's catalog, and a write's. */
internal fun Connection.customFormats(): List<CustomFormat> {
    return prepareStatement(
        """
        SELECT format, name, derives_from, points_budget, singleton,
               CASE WHEN json_valid(aliases) AND json_type(aliases) = 'array'
                    THEN (SELECT GROUP_CONCAT(value, char(31)) FROM json_each(custom_formats.aliases)) END AS alias_list
        FROM custom_formats
        """.trimIndent(),
    ).use { st ->
        st.executeQuery().use { rs ->
            rs.rows {
                CustomFormat(
                    key = getString("format"), name = getString("name"),
                    aliases = getString("alias_list")?.split('\u001F').orEmpty(),
                    derivesFrom = getString("derives_from"),
                    pointsBudget = getInt("points_budget").takeIf { !wasNull() },
                    singleton = getInt("singleton") != 0,
                )
            }
        }
    }
}

/** [raw] as a deck or a folder stores it (FormatCatalog.canonical), read in the writing transaction. */
internal fun Connection.canonicalFormat(raw: String?): String? = FormatCatalog(customFormats()).canonical(raw)
