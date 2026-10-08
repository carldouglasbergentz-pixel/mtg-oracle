package mtgoracle.data

import mtgoracle.core.limited.LimitedSet
import mtgoracle.core.limited.OpenedPool
import mtgoracle.core.limited.PoolCard
import java.sql.Connection

/** A pool as stored: what was opened, by whom, and the pool it was opened against (the AI's, or the other person's). */
data class StoredPool(val id: Int, val pool: OpenedPool, val product: String, val openedBy: String, val rivalPoolId: Int?)

/**
 * The limited pools (limited_pools, limited_pool_cards). A pool is written
 * once, as it was opened, and never changed: it is what a deck built from it
 * is held to. Writing one with its deck is [DeckWriter.createFromPool], in
 * one transaction.
 */
class PoolStore(private val db: MtgDb) {

    fun pool(id: Int): StoredPool? = db.read { conn ->
        val head = conn.query("SELECT set_code, scryfall_code, set_name, product, packs, seed, opened_by, rival_pool_id FROM limited_pools WHERE id = ?", id) {
            Head(getString("set_code"), getString("scryfall_code"), getString("set_name"), getString("product"), getInt("packs"), getLong("seed"), getString("opened_by"),
                getObject("rival_pool_id")?.let { (it as Number).toInt() })
        }.firstOrNull() ?: return@read null
        val cards = conn.query("SELECT pack_no, card_name, set_code, collector_number, foil FROM limited_pool_cards WHERE pool_id = ? ORDER BY pack_no, id", id) {
            getInt("pack_no") to PoolCard(getString("card_name"), getString("set_code").orEmpty(), getString("collector_number"), getInt("foil") != 0)
        }
        val packs = (1..head.packs).map { n -> cards.filter { it.first == n }.map { it.second } }
        StoredPool(id, OpenedPool(LimitedSet(head.setCode, head.scryfallCode, head.setName, released = ""), head.seed, packs), head.product, head.openedBy, head.rivalPoolId)
    }

    /**
     * [rival] (the other person's pool at a network table, opened by
     * [openedBy]) stored and linked to pool [poolId], which had none: what
     * the deck built from [poolId] was played against.
     */
    fun addRival(poolId: Int, rival: OpenedPool, product: String, openedBy: String, forgeVersion: String?): Int = db.write { conn ->
        val id = insert(conn, rival, product, openedBy, forgeVersion, rivalPoolId = null)
        conn.update("UPDATE limited_pools SET rival_pool_id = ? WHERE id = ? AND rival_pool_id IS NULL", id, poolId)
        id
    }

    private data class Head(val setCode: String, val scryfallCode: String, val setName: String, val product: String, val packs: Int, val seed: Long, val openedBy: String, val rivalPoolId: Int?)

    companion object {
        /** Writes [pool] as opened by [openedBy] (`me`, `ai`, or a person's name), card by card, pack by pack. Returns its id. */
        internal fun insert(conn: Connection, pool: OpenedPool, product: String, openedBy: String, forgeVersion: String?, rivalPoolId: Int?): Int {
            val id = conn.insert(
                "INSERT INTO limited_pools (set_code, scryfall_code, set_name, product, packs, seed, opened_by, rival_pool_id, forge_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                pool.set.code, pool.set.scryfallCode, pool.set.name, product, pool.packs.size, pool.seed, openedBy, rivalPoolId, forgeVersion, LibraryWriter.now(),
            ).toInt()
            pool.packs.forEachIndexed { index, pack ->
                for (card in pack) {
                    conn.update(
                        "INSERT INTO limited_pool_cards (pool_id, pack_no, card_name, set_code, collector_number, foil) VALUES (?, ?, ?, ?, ?, ?)",
                        id, index + 1, card.name, card.setCode, card.collectorNumber, if (card.foil) 1 else 0,
                    )
                }
            }
            return id
        }
    }
}
