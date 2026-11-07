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

    /** cosmetics.json (Lunar's current list): its fields as the index's columns, companions as before. */
    @Test
    fun cosmeticsJson() {
        val json = """[
            {"id":9433,"name":"Lunar Purple","resource":"lunar:cosmetics/cloaks/lunarpurp.webp","category":"cloak","indexType":"NONE",
             "morph":"","morphDuration":null,"geckolibCosmetic":false,"special":false,"animated":true,"colors":["WHITE","PURPLE"],
             "tags":["AESTHETIC"],"releasedAt":"2026-06-15T18:25:13.000Z"},
            {"id":5995,"name":"Easter Petals","resource":"lunar:cosmetics/models/gek/auras/easter_petals/easter_petals.gek.json",
             "category":"auras","indexType":"NONE","morph":"easter_petals","geckolibCosmetic":true,"colors":["PINK"],"tags":["HOLIDAY"]},
            {"id":3795,"name":"Bucket Hat V2 (Purple Wool)","resource":"lunar:cosmetics/models/hats/lunar_bucket_hat_v2/textures/woolpurple.webp",
             "category":"hat","indexType":"lunar_bucket_hat_v2","morph":null,"geckolibCosmetic":false,"colors":[],"tags":[]},
            {"id":9816,"name":"Slasher Dog","resource":"lunar:cosmetics/models/gek/companions/ghost_dog/textures/slasher_dog.gek.json","category":"companion"},
            {"name":"no id","resource":"lunar:x.webp","category":"cloak"}
        ]"""
        val all = LunarCosmeticIndex.parseCosmetics(json)
        assertEquals(setOf(9433, 5995, 3795, 9816), all.keys)
        val purple = all[9433]!!
        assertEquals("Lunar Purple", purple.name)
        assertEquals("cloak", purple.type)
        assertEquals("cosmetics/cloaks/lunarpurp.webp", purple.path)
        assertEquals("assets/lunar/cosmetics/cloaks/lunarpurp.webp", purple.assetPath)
        assertEquals(true, purple.isCloak)
        assertEquals(setOf("WHITE", "PURPLE"), purple.colors)
        assertEquals(setOf("AESTHETIC"), purple.categories)
        assertEquals("easter_petals", all[5995]!!.particle)
        assertEquals(true, all[5995]!!.gek)
        assertEquals("lunar_bucket_hat_v2", all[3795]!!.legacyModel)
        assertEquals("", all[3795]!!.particle)
        assertEquals("companion", all[9816]!!.type)
        assertEquals(setOf(9816), LunarCosmeticIndex.parseCompanions(json).keys)
    }

    @Test
    fun stylesAndLabels() {
        assertEquals(listOf("LOGO", "FIRE", "8_BIT", "SHINE", "SIMPLE"), LockerFilter.styles(all))
        assertEquals("8-bit", LockerFilter.label("8_BIT"))
        assertEquals("Graffiti", LockerFilter.label("GRAFFETI"))
        assertEquals("Fire", LockerFilter.label("FIRE"))
    }
}
