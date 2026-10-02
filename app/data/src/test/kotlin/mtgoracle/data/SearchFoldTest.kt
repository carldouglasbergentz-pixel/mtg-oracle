package mtgoracle.data

import mtgoracle.core.lookup.NameFold
import mtgoracle.core.lookup.SearchLanguage
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A name typed without its accents finds the card, as `card` does: SQLite's
 * NOCASE folds ASCII only, so `eowyn` found no Éowyn (97 card names have
 * characters past ASCII). Names compare through the SQL `fold()` MtgDb adds.
 */
class SearchFoldTest {
    @Test
    fun `a name typed without its accents finds the card, in free text, n colon and n equals`() {
        val accented = DriverManager.getConnection("jdbc:sqlite:${FixtureDb.file.path}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name FROM cards WHERE name GLOB '*[^ -~]*' AND name NOT LIKE '%//%' AND name NOT LIKE 'A-%' ORDER BY name").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
        assertTrue(accented.isNotEmpty(), "the fixture has a name with accents")
        val lookup = Lookup(MtgDb(FixtureDb.file))
        for (name in accented.take(5)) {
            val plain = NameFold.fold(name)
            for (query in listOf(plain, "n:\"$plain\"", "n=\"$plain\"")) {
                val names = lookup.search.page(SearchLanguage.parse(query), pageSize = 200).rows.map { it.name }
                assertTrue(name in names, "'$query' finds $name: $names")
            }
        }
    }
}
