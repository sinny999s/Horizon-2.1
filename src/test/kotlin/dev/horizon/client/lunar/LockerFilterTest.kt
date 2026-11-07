package dev.horizon.client.lunar

import kotlin.test.Test
import kotlin.test.assertEquals

/** The locker's filters ([LockerFilter]) and the index's colour and style columns they read. */
class LockerFilterTest {
    private val index = LunarCosmeticIndex.parseIndex(
        """
        6976,,,EuropeMC Crystal,,true,cloak,NONE,cosmetics/cloaks/europemc_crystal.webp,false,LOGO,BLACK|PURPLE|GREY
        4043,,,Fire (Orange),,true,cloak,NONE,cosmetics/cloaks/lunarfire-orange.webp,false,FIRE|LOGO,WHITE|BLACK|ORANGE
        3511,,,MinuteTech Fire (Orange),,true,cloak,NONE,cosmetics/cloaks/minutetechorangefire.webp,false,FIRE,BLACK|RED|ORANGE
        1882,,,Lunar+ Cloak (White & Pink),,true,cloak,NONE,cosmetics/cloaks/lunarpluswhitepink.webp,false,SIMPLE|SHINE|LOGO,WHITE|PINK
        1000,,,"Art, Pixel",,true,cloak,NONE,cosmetics/cloaks/x.webp,false,8_BIT,
        """.trimIndent()
    )
    private val all = index.values
    private fun ids(q: String = "", f: LockerFilter.Filter = LockerFilter.Filter(), owned: Set<Int> = emptySet(), picked: Set<Int> = emptySet()) =
        LockerFilter.apply(all, q, f, { it in owned }, { it in picked }).map { it.id }

    @Test
    fun tagsFromTheIndex() {
        assertEquals(setOf("FIRE", "LOGO"), index[4043]!!.categories)
        assertEquals(setOf("WHITE", "BLACK", "ORANGE"), index[4043]!!.colors)
        assertEquals(emptySet(), index[1000]!!.colors)
        assertEquals("\"Art, Pixel\"", index[1000]!!.name)
    }

    @Test
    fun filters() {
        assertEquals(listOf(6976, 4043, 3511, 1882, 1000), ids())
        assertEquals(listOf(4043, 3511), ids(f = LockerFilter.Filter(style = "FIRE")))
        assertEquals(listOf(4043, 1882), ids(f = LockerFilter.Filter(color = "WHITE")))
        assertEquals(listOf(4043), ids(f = LockerFilter.Filter(color = "WHITE", style = "FIRE")))
        assertEquals(listOf(1882), ids(f = LockerFilter.Filter(show = LockerFilter.Show.OWNED), owned = setOf(1882)))
        assertEquals(listOf(6976), ids(f = LockerFilter.Filter(show = LockerFilter.Show.PICKED), picked = setOf(6976)))
        // The search matches names and style tags.
        assertEquals(listOf(4043, 3511), ids("fire"))
        assertEquals(listOf(1882), ids("shine"))
        assertEquals(listOf(1000), ids("8-bit"))
        // By name, ignoring case.
        assertEquals(listOf(1000, 6976, 4043, 1882, 3511), ids(f = LockerFilter.Filter(sort = LockerFilter.Sort.NAME)))
    }

    @Test
    fun stylesAndLabels() {
        assertEquals(listOf("LOGO", "FIRE", "8_BIT", "SHINE", "SIMPLE"), LockerFilter.styles(all))
        assertEquals("8-bit", LockerFilter.label("8_BIT"))
        assertEquals("Graffiti", LockerFilter.label("GRAFFETI"))
        assertEquals("Fire", LockerFilter.label("FIRE"))
    }
}
