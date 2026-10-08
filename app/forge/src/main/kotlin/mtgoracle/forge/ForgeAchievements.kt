package mtgoracle.forge

import forge.game.GameType
import forge.localinstance.achievements.AltWinAchievements
import forge.localinstance.achievements.CardActivationAchievements
import forge.localinstance.achievements.ChallengeAchievements
import forge.localinstance.achievements.PlaneswalkerAchievements
import forge.model.FModel
import mtgoracle.core.play.Achievement
import mtgoracle.core.play.AchievementGroup
import mtgoracle.core.play.AchievementLevel
import mtgoracle.core.play.AchievementTier
import forge.localinstance.achievements.Achievement as ForgeAchievement

/**
 * Forge's achievements, read for the achievements view: the collections a
 * game in this app adds to. A constructed game is Constructed to Forge (the
 * Commander variants too), a limited one Sealed (FModel.getAchievements), and
 * the four card-and-challenge collections are updated after any game
 * (AchievementCollection.updateAll). Forge must be up.
 */
object ForgeAchievements {
    fun groups(): List<AchievementGroup> = listOf(
        FModel.getAchievements(GameType.Constructed), FModel.getAchievements(GameType.Sealed),
        AltWinAchievements.instance, PlaneswalkerAchievements.instance, ChallengeAchievements.instance, CardActivationAchievements.instance,
    ).map { collection -> AchievementGroup(collection.toString(), collection.map(::view)) }

    private fun view(a: ForgeAchievement): Achievement {
        val best = runCatching { a.getSubTitle(true) }.getOrNull()
        val card = runCatching { a.paperCard?.name }.getOrNull()
        if (a.isSpecial) {
            // A special one's flavour text is passed as its mythic description, in parentheses.
            return Achievement(a.displayName, a.sharedDesc.orEmpty(), emptyList(), a.isActive, best, card,
                flavor = a.mythicDesc?.trim()?.removePrefix("(")?.removeSuffix(")")?.takeIf { it.isNotBlank() })
        }
        val levels = listOf(
            Triple(AchievementTier.COMMON, a.commonDesc, a.earnedCommon()),
            Triple(AchievementTier.UNCOMMON, a.uncommonDesc, a.earnedUncommon()),
            Triple(AchievementTier.RARE, a.rareDesc, a.earnedRare()),
            Triple(AchievementTier.MYTHIC, a.mythicDesc, a.earnedMythic()),
        ).mapNotNull { (tier, text, earned) -> text?.takeIf { it.isNotBlank() }?.let { AchievementLevel(tier, it, earned) } }
        return Achievement(a.displayName, a.sharedDesc.orEmpty(), levels, a.isActive, best, card)
    }
}
