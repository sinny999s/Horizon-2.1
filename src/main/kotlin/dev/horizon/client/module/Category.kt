package dev.horizon.client.module

/** A sub-category: one tab of a ClickGUI panel. Its label is the id with the first letter upper-cased. */
class SubCategory(val id: String) {
    val displayName = id.replaceFirstChar { it.uppercaseChar() }
}

/** A ClickGUI panel: its title is the id with the first letter upper-cased, its tabs are [subCategories] in order. */
class Category(val id: String, val subCategories: List<SubCategory>) {
    val displayName = id.replaceFirstChar { it.uppercaseChar() }
}

/** The categories, in panel order. */
object Categories {
    val ATTACK = SubCategory("attack")
    val LEGIT = SubCategory("legit")
    val PROTECT = SubCategory("protect")
    val BASIC = SubCategory("basic")
    val RAGE = SubCategory("rage")
    val COSMETIC = SubCategory("cosmetic")
    val ESP = SubCategory("esp")
    val WORLD = SubCategory("world")
    val EQUIP = SubCategory("equip")
    val PLAYER = SubCategory("player")
    val MISC = SubCategory("misc")

    val PVP = Category("pvp", listOf(ATTACK, LEGIT, PROTECT))
    val MOVEMENT = Category("movement", listOf(BASIC, RAGE))
    val VISUAL = Category("visual", listOf(COSMETIC, ESP))
    val UTILITY = Category("utility", listOf(WORLD, EQUIP, PLAYER, MISC))

    val ALL = listOf(PVP, MOVEMENT, VISUAL, UTILITY)
}
