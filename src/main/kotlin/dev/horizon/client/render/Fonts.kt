package dev.horizon.client.render

/** Font registry: each getter is one fixed font. Sizes are GUI units. */
object Fonts {
    /** Help text, slider widgets. */
    val medium75 by lazy { TextRenderer("sf_medium", 7.5f) }

    /** Most setting widgets. */
    val medium8 by lazy { TextRenderer("sf_medium", 8f) }

    /** Module names, tabs. */
    val medium95 by lazy { TextRenderer("sf_medium", 9.5f) }

    /** Small labels in widgets. */
    val medium65 by lazy { TextRenderer("sf_medium", 6.5f) }

    val bold12 by lazy { TextRenderer("sf_bold", 12f) }

    val bold8 by lazy { TextRenderer("sf_bold", 8f) }

    val bold20 by lazy { TextRenderer("sf_bold", 20f) }

    val bold25 by lazy { TextRenderer("sf_bold", 25f) }

    /** Panel titles. */
    val semibold12 by lazy { TextRenderer("sf_semibold", 12f) }

    /** "Toggle"/"Hold" in the bind popup. */
    val regular75 by lazy { TextRenderer("sf_regular", 7.5f) }

    /** The Hunt Map's top bar. Semibold here looks like a browser's medium (the mockup's) at this size. */
    val semibold8 by lazy { TextRenderer("sf_semibold", 8f) }

    /** The Hunt Map's group labels. */
    val semibold65 by lazy { TextRenderer("sf_semibold", 6.5f) }
}
