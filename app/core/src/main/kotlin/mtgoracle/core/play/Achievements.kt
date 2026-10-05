package mtgoracle.core.play

/** A tiered achievement's levels, lowest first, as Forge names them. */
enum class AchievementTier(val label: String) { COMMON("common"), UNCOMMON("uncommon"), RARE("rare"), MYTHIC("mythic") }

/** One level of an achievement: what it takes, and whether it is reached. */
data class AchievementLevel(val tier: AchievementTier, val text: String, val earned: Boolean)

/**
 * One of Forge's achievements as the achievements view shows it. A tiered
 * one has [levels] ("Win a game by turn 5" common, by turn 3 mythic); a
 * special one has none and is either earned or not, with [flavor] beside its
 * [description]. [best] is Forge's own line for the best so far
 * (`Best: 4 turns (2026-10-05)`), [card] the card a special one is about
 * (Jace's Lobotomy: Jace, the Mind Sculptor).
 */
data class Achievement(
    val name: String,
    val description: String,
    val levels: List<AchievementLevel>,
    val earned: Boolean,
    val best: String? = null,
    val card: String? = null,
    val flavor: String? = null,
) {
    val special: Boolean get() = levels.isEmpty()
    /** The highest level reached, for a tiered one. */
    val tier: AchievementTier? get() = levels.lastOrNull { it.earned }?.tier
}

/** One of Forge's collections ("Constructed", "Planeswalker Ultimates"), in Forge's order. */
data class AchievementGroup(val name: String, val achievements: List<Achievement>) {
    val earned: Int get() = achievements.count { it.earned }
}
