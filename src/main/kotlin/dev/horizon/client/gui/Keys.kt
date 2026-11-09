package dev.horizon.client.gui

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.systems.RenderSystem
import dev.horizon.client.setting.Bind
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen

/**
 * Key names and held-state checks. The names were for GLFW key codes; 26.3 uses SDL scancodes and
 * counts mouse buttons from 1, so the same names are mapped onto the game's codes here.
 */
object Keys {
    const val LEFT = InputConstants.KEY_LEFT
    const val RIGHT = InputConstants.KEY_RIGHT
    const val UP = InputConstants.KEY_UP
    const val DOWN = InputConstants.KEY_DOWN
    const val ESCAPE = InputConstants.KEY_ESCAPE
    const val ENTER = InputConstants.KEY_RETURN
    const val BACKSPACE = InputConstants.KEY_BACKSPACE
    const val DELETE = InputConstants.KEY_DELETE
    const val LSHIFT = InputConstants.KEY_LSHIFT
    const val LCONTROL = InputConstants.KEY_LCONTROL
    const val LALT = InputConstants.KEY_LALT
    const val TAB = InputConstants.KEY_TAB
    const val F = InputConstants.KEY_F
    const val V = InputConstants.KEY_V
    const val P = InputConstants.KEY_P

    const val MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT
    const val MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT
    const val MOUSE_MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE

    private val mouseNames = mapOf(
        InputConstants.MOUSE_BUTTON_LEFT to "LMB", InputConstants.MOUSE_BUTTON_RIGHT to "RMB",
        InputConstants.MOUSE_BUTTON_MIDDLE to "MMB", InputConstants.MOUSE_BUTTON_4 to "Mouse 4",
        InputConstants.MOUSE_BUTTON_5 to "Mouse 5",
    )

    private val keyNames: Map<Int, String> = buildMap {
        put(InputConstants.KEY_SPACE, "Space")
        put(InputConstants.KEY_APOSTROPHE, "Apostrophe")
        put(InputConstants.KEY_COMMA, "Comma")
        put(InputConstants.KEY_MINUS, "Minus")
        put(InputConstants.KEY_PERIOD, "Period")
        put(InputConstants.KEY_SLASH, "Slash")
        val digits = intArrayOf(
            InputConstants.KEY_0, InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4,
            InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9,
        )
        digits.forEachIndexed { i, k -> put(k, i.toString()) }
        put(InputConstants.KEY_SEMICOLON, "SemiColon")
        put(InputConstants.KEY_EQUALS, "Equal")
        for (c in 'A'..'Z') put(InputConstants.KEY_A + (c - 'A'), c.toString())
        put(InputConstants.KEY_LBRACKET, "LeftBracket")
        put(InputConstants.KEY_BACKSLASH, "BackSlash")
        put(InputConstants.KEY_RBRACKET, "RightBracket")
        put(InputConstants.KEY_GRAVE, "Grave")
        put(InputConstants.KEY_ESCAPE, "Escape")
        put(InputConstants.KEY_RETURN, "Enter")
        put(InputConstants.KEY_TAB, "Tab")
        put(InputConstants.KEY_BACKSPACE, "BackSpace")
        put(InputConstants.KEY_INSERT, "Insert")
        put(InputConstants.KEY_DELETE, "Delete")
        put(InputConstants.KEY_RIGHT, "Right")
        put(InputConstants.KEY_LEFT, "Left")
        put(InputConstants.KEY_DOWN, "Down")
        put(InputConstants.KEY_UP, "Up")
        put(InputConstants.KEY_PAGEUP, "PageUp")
        put(InputConstants.KEY_PAGEDOWN, "PageDown")
        put(InputConstants.KEY_HOME, "Home")
        put(InputConstants.KEY_END, "End")
        put(InputConstants.KEY_CAPSLOCK, "CapsLock")
        put(InputConstants.KEY_SCROLLLOCK, "ScrollLock")
        put(InputConstants.KEY_NUMLOCK, "NumLock")
        put(InputConstants.KEY_PRINTSCREEN, "PrintScreen")
        put(InputConstants.KEY_PAUSE, "Pause")
        val f = intArrayOf(
            InputConstants.KEY_F1, InputConstants.KEY_F2, InputConstants.KEY_F3, InputConstants.KEY_F4, InputConstants.KEY_F5,
            InputConstants.KEY_F6, InputConstants.KEY_F7, InputConstants.KEY_F8, InputConstants.KEY_F9, InputConstants.KEY_F10,
            InputConstants.KEY_F11, InputConstants.KEY_F12, InputConstants.KEY_F13, InputConstants.KEY_F14, InputConstants.KEY_F15,
            InputConstants.KEY_F16, InputConstants.KEY_F17, InputConstants.KEY_F18, InputConstants.KEY_F19, InputConstants.KEY_F20,
            InputConstants.KEY_F21, InputConstants.KEY_F22, InputConstants.KEY_F23, InputConstants.KEY_F24,
        )
        f.forEachIndexed { i, k -> put(k, "F${i + 1}") }
        val num = intArrayOf(
            InputConstants.KEY_NUMPAD0, InputConstants.KEY_NUMPAD1, InputConstants.KEY_NUMPAD2, InputConstants.KEY_NUMPAD3,
            InputConstants.KEY_NUMPAD4, InputConstants.KEY_NUMPAD5, InputConstants.KEY_NUMPAD6, InputConstants.KEY_NUMPAD7,
            InputConstants.KEY_NUMPAD8, InputConstants.KEY_NUMPAD9,
        )
        num.forEachIndexed { i, k -> put(k, "NUM $i") }
        put(99, "Deci") // SDL keypad period
        put(84, "Div") // SDL keypad divide
        put(InputConstants.KEY_MULTIPLY, "Mul")
        put(86, "Sub") // SDL keypad minus
        put(InputConstants.KEY_ADD, "Add")
        put(InputConstants.KEY_NUMPADENTER, "Enter")
        put(InputConstants.KEY_NUMPADEQUALS, "Equal")
        put(InputConstants.KEY_LSHIFT, "Shift")
        put(InputConstants.KEY_LCONTROL, "LCtrl")
        put(InputConstants.KEY_LALT, "LAlt")
        put(InputConstants.KEY_LGUI, "LSuper")
        put(InputConstants.KEY_RSHIFT, "RShift")
        put(InputConstants.KEY_RCONTROL, "RCtrl")
        put(InputConstants.KEY_RALT, "RAlt")
        put(InputConstants.KEY_RGUI, "RSuper")
        put(101, "Menu") // SDL application key
    }

    /** The game's key code for a key name, or -1. */
    fun code(name: String): Int = keyNames.entries.firstOrNull { it.value == name }?.key ?: -1

    /** The key's name, "None" when unknown. */
    fun name(bind: Bind): String = name(bind.key, bind.mouse)

    fun name(key: Int, mouse: Boolean): String = (if (mouse) mouseNames[key] else keyNames[key]) ?: "None"

    /** Mouse buttons held right now, kept by the input mixin. */
    val heldMouse = HashSet<Int>()

    /** Is a key held (false off the render thread or while chat is open). */
    fun down(key: Int): Boolean {
        if (!RenderSystem.isOnRenderThread()) return false
        if (key == -1) return false
        if (Minecraft.getInstance().gui.screen() is ChatScreen) return false
        return InputConstants.isKeyDown(key)
    }
}
