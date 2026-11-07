package dev.horizon.client.lunar

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Not in the original: Molang, Bedrock's expression language, as Lunar's cosmetics use it (their animations' keyframes,
 * animation state machines, and Lunar's own `constants.molang` and `functions.molang`). Game-free.
 *
 * What's understood: numbers, `'strings'` (compared by value), `true`/`false`; `+ - * / %`, `! && ||`, comparisons,
 * `?:` and binary `?`, `??`; assignment to `v.`/`variable.`, `t.`/`temp.`, `c.`/`context.`; statements separated by `;`,
 * `{ }` blocks, `return`, Lunar's C-style `if (...) { } else { }`, `loop(n, { })`, `break`, `continue`; `math.*` (angles
 * in degrees, as Bedrock has them); `q.`/`query.` values supplied by the caller ([Scope.query]); and functions defined in
 * Molang ([parseFunctions], called as `lunar.name(args)`, the arguments being `a.<name>`). Anything unknown reads as 0.
 *
 * A program with a single expression and no `;` has that expression's value; otherwise its `return`'s (0 without one).
 */
object Molang {
    /** A user function: its parameter names (without `a.`) and body. */
    class Function(val params: List<String>, val body: Node)

    /** Where a program runs: its variables, the query values, functions, and a random source. */
    class Scope(
        /** `v.` variables: kept for one entity across frames. */
        val variables: HashMap<String, Double> = HashMap(),
        /** `c.` context: also kept (Lunar's constants.molang writes these every frame). */
        val context: HashMap<String, Double> = HashMap(),
        val functions: Map<String, Function> = emptyMap(),
        val random: java.util.Random = java.util.Random(),
    ) {
        /** `q.` values (names without the prefix). Missing ones are 0. */
        var query: (String) -> Double = { 0.0 }

        /** `option.` values (Lunar's cosmetic options, by name). Missing ones are undefined (`??` falls through). */
        var options: Map<String, Double> = emptyMap()
        internal var temps = HashMap<String, Double>()
        internal var args: Map<String, Double> = emptyMap()
        internal var depth = 0
    }

    /** A parsed program or expression. */
    abstract class Node {
        internal abstract fun eval(s: Scope, f: Flow): Double

        /** Runs this as a whole program: temps start empty, the value is as described in [Molang]. */
        fun run(s: Scope): Double {
            s.temps.clear()
            return eval(s, Flow())
        }
    }

    internal class Flow {
        var returned = false
        var value = 0.0
        var breaking = false
        var continuing = false
        val stopped get() = returned || breaking || continuing
    }

    private val cache = HashMap<String, Node>()

    /** [src] parsed, cached by its text. A program that doesn't parse is 0. */
    fun compile(src: String): Node = synchronized(cache) {
        cache.getOrPut(src) {
            try {
                Parser(src).program(topLevel = true)
            } catch (e: Exception) {
                Const(0.0)
            }
        }
    }

    /** Why [src] doesn't parse, or null when it does (for tests). */
    fun problem(src: String): String? = try {
        Parser(src).program(topLevel = true)
        null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    /** A number or a Molang string from a JSON value. */
    fun constOrCompile(v: Any?): Node = when (v) {
        is Number -> Const(v.toDouble())
        is String -> v.trim().toDoubleOrNull()?.let { Const(it) } ?: compile(v)
        is Boolean -> Const(if (v) 1.0 else 0.0)
        else -> Const(0.0)
    }

    /**
     * Lunar's `functions.molang`: `lunar.name(a.x, a.y): |-` lines, each followed by its indented body. Returns the
     * functions by full name ("lunar.name").
     */
    fun parseFunctions(text: String): Map<String, Function> {
        val out = HashMap<String, Function>()
        val header = Regex("""^([A-Za-z_][\w.]*)\s*\(([^)]*)\)\s*:\s*\|-?\s*$""")
        var name: String? = null
        var params: List<String> = emptyList()
        val body = StringBuilder()
        fun flush() {
            val n = name ?: return
            try {
                out[n] = Function(params, Parser(body.toString()).program(topLevel = false))
            } catch (_: Exception) {
            }
            body.setLength(0)
        }
        for (line in text.lines()) {
            val m = header.find(line.trimEnd())
            if (m != null && !line.startsWith(" ") && !line.startsWith("\t")) {
                flush()
                name = m.groupValues[1].lowercase()
                params = m.groupValues[2].split(',').map { it.trim().lowercase().removePrefix("a.").removePrefix("arg.") }.filter { it.isNotEmpty() }
            } else if (name != null) body.append(line).append('\n')
        }
        flush()
        return out
    }

    // ---- nodes ----

    private class Const(val v: Double) : Node() {
        override fun eval(s: Scope, f: Flow) = v
    }

    private enum class Space { QUERY, VARIABLE, TEMP, CONTEXT, ARG, OPTION, OTHER }

    private class Var(val space: Space, val name: String) : Node() {
        override fun eval(s: Scope, f: Flow): Double = when (space) {
            Space.QUERY -> s.query(name)
            Space.VARIABLE -> s.variables[name] ?: 0.0
            Space.TEMP -> s.temps[name] ?: 0.0
            Space.CONTEXT -> s.context[name] ?: 0.0
            Space.ARG -> s.args[name] ?: 0.0
            Space.OPTION -> s.options[name] ?: 0.0
            Space.OTHER -> when (name) {
                "math.pi" -> Math.PI
                "true" -> 1.0
                else -> 0.0
            }
        }

        fun defined(s: Scope): Boolean = when (space) {
            Space.VARIABLE -> name in s.variables
            Space.TEMP -> name in s.temps
            Space.CONTEXT -> name in s.context
            Space.ARG -> name in s.args
            Space.OPTION -> name in s.options
            Space.QUERY -> true
            Space.OTHER -> name == "math.pi" || name == "true"
        }

        fun set(s: Scope, value: Double) {
            when (space) {
                Space.VARIABLE -> s.variables[name] = value
                Space.TEMP -> s.temps[name] = value
                Space.CONTEXT -> s.context[name] = value
                else -> {}
            }
        }
    }

    private class Assign(val target: Var, val value: Node) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            val v = value.eval(s, f)
            target.set(s, v)
            return v
        }
    }

    private class Unary(val op: Char, val a: Node) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            val v = a.eval(s, f)
            return when (op) {
                '-' -> -v
                '!' -> if (v == 0.0) 1.0 else 0.0
                else -> v
            }
        }
    }

    private class Binary(val op: String, val a: Node, val b: Node) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            when (op) {
                "&&" -> return if (a.eval(s, f) != 0.0 && b.eval(s, f) != 0.0) 1.0 else 0.0
                "||" -> return if (a.eval(s, f) != 0.0 || b.eval(s, f) != 0.0) 1.0 else 0.0
                "??" -> {
                    // A variable that was never set falls through; any other value is kept (even 0).
                    if (a is Var) return if (a.defined(s)) a.eval(s, f) else b.eval(s, f)
                    val x = a.eval(s, f)
                    return if (x != 0.0) x else b.eval(s, f)
                }
            }
            val x = a.eval(s, f)
            val y = b.eval(s, f)
            return when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> if (y == 0.0) 0.0 else x / y
                "%" -> if (y == 0.0) 0.0 else x % y
                "<" -> b(x < y)
                "<=" -> b(x <= y)
                ">" -> b(x > y)
                ">=" -> b(x >= y)
                "==" -> b(x == y)
                "!=" -> b(x != y)
                else -> 0.0
            }
        }
    }

    private fun b(v: Boolean) = if (v) 1.0 else 0.0

    private class Ternary(val c: Node, val a: Node, val b: Node?) : Node() {
        override fun eval(s: Scope, f: Flow): Double =
            if (c.eval(s, f) != 0.0) a.eval(s, f) else b?.eval(s, f) ?: 0.0
    }

    /** Statements; a block's value is its last statement's. */
    private class Block(val statements: List<Node>, val single: Boolean) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            var last = 0.0
            for (st in statements) {
                last = st.eval(s, f)
                if (f.stopped) return if (f.returned) f.value else last
            }
            return if (single) last else last
        }
    }

    /** A whole program: a lone expression's value, else its return value. */
    private class Program(val block: Block, val expression: Boolean) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            val v = block.eval(s, f)
            return when {
                f.returned -> f.value
                expression -> v
                else -> 0.0
            }
        }
    }

    private class Return(val value: Node?) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            val v = value?.eval(s, f) ?: 0.0
            if (!f.stopped) {
                f.returned = true
                f.value = v
            }
            return v
        }
    }

    private class If(val cond: Node, val then: Node, val otherwise: Node?) : Node() {
        override fun eval(s: Scope, f: Flow): Double = if (cond.eval(s, f) != 0.0) then.eval(s, f) else otherwise?.eval(s, f) ?: 0.0
    }

    private class Loop(val count: Node, val body: Node) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            val n = count.eval(s, f).toInt().coerceIn(0, 1024)
            for (i in 0 until n) {
                body.eval(s, f)
                if (f.returned) return f.value
                if (f.breaking) {
                    f.breaking = false
                    break
                }
                f.continuing = false
            }
            return 0.0
        }
    }

    private class Jump(val breaking: Boolean) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            if (breaking) f.breaking = true else f.continuing = true
            return 0.0
        }
    }

    private class Call(val name: String, val args: List<Node>) : Node() {
        override fun eval(s: Scope, f: Flow): Double {
            val x = DoubleArray(args.size) { args[it].eval(s, f) }
            fun a(i: Int) = if (i < x.size) x[i] else 0.0
            return when (name) {
                "math.abs" -> abs(a(0))
                "math.sin" -> Math.sin(Math.toRadians(a(0)))
                "math.cos" -> Math.cos(Math.toRadians(a(0)))
                "math.asin" -> Math.toDegrees(Math.asin(a(0).coerceIn(-1.0, 1.0)))
                "math.acos" -> Math.toDegrees(Math.acos(a(0).coerceIn(-1.0, 1.0)))
                "math.atan" -> Math.toDegrees(Math.atan(a(0)))
                "math.atan2" -> Math.toDegrees(Math.atan2(a(0), a(1)))
                "math.clamp" -> a(0).coerceIn(minOf(a(1), a(2)), maxOf(a(1), a(2)))
                "math.lerp" -> a(0) + (a(1) - a(0)) * a(2)
                "math.inverse_lerp" -> if (a(1) == a(0)) 0.0 else (a(2) - a(0)) / (a(1) - a(0))
                "math.lerprotate" -> {
                    var d = ((a(1) - a(0)) % 360 + 540) % 360 - 180
                    if (d == -180.0) d = 180.0
                    a(0) + d * a(2)
                }
                "math.min" -> minOf(a(0), a(1))
                "math.max" -> maxOf(a(0), a(1))
                "math.pow" -> Math.pow(a(0), a(1))
                "math.sqrt" -> Math.sqrt(maxOf(0.0, a(0)))
                "math.exp" -> Math.exp(a(0))
                "math.ln" -> if (a(0) > 0) Math.log(a(0)) else 0.0
                "math.floor" -> floor(a(0))
                "math.ceil" -> Math.ceil(a(0))
                "math.round" -> round(a(0))
                "math.trunc" -> a(0).toLong().toDouble()
                "math.mod" -> if (a(1) == 0.0) 0.0 else a(0) % a(1)
                "math.sign" -> Math.signum(a(0))
                "math.copy_sign" -> Math.copySign(a(0), a(1))
                "math.hermite_blend" -> a(0) * a(0) * (3 - 2 * a(0))
                "math.random" -> a(0) + s.random.nextDouble() * (a(1) - a(0))
                "math.random_integer" -> {
                    val lo = minOf(a(0), a(1)).toLong()
                    val hi = maxOf(a(0), a(1)).toLong()
                    (lo + (s.random.nextDouble() * (hi - lo + 1)).toLong()).toDouble()
                }
                "math.die_roll" -> (0 until a(0).toInt().coerceIn(0, 64)).sumOf { a(1) + s.random.nextDouble() * (a(2) - a(1)) }
                "math.die_roll_integer" -> (0 until a(0).toInt().coerceIn(0, 64)).sumOf {
                    (a(1).toLong() + (s.random.nextDouble() * (a(2).toLong() - a(1).toLong() + 1)).toLong()).toDouble()
                }
                "math.pi" -> Math.PI
                else -> user(s, x)
            }
        }

        private fun user(s: Scope, x: DoubleArray): Double {
            // Lunar's built-in lunar.* functions (biome checks, light level...) come from the caller's queries.
            val fn = s.functions[name] ?: return if (name.startsWith("lunar.")) s.query(name) else 0.0
            if (s.depth > 32) return 0.0
            val saved = s.args
            val savedTemps = s.temps
            s.args = HashMap<String, Double>().also { m -> fn.params.forEachIndexed { i, p -> m[p] = if (i < x.size) x[i] else 0.0 } }
            s.temps = HashMap()
            s.depth++
            try {
                val f = Flow()
                val v = fn.body.eval(s, f)
                return if (f.returned) f.value else v
            } finally {
                s.depth--
                s.args = saved
                s.temps = savedTemps
            }
        }
    }

    // ---- parsing ----

    private class Parser(val src: String) {
        private val toks = tokenize(src)
        private var i = 0

        private fun peek(k: Int = 0) = toks.getOrNull(i + k) ?: ""
        private fun next() = toks.getOrNull(i++) ?: ""
        private fun eat(t: String): Boolean = if (peek() == t) { i++; true } else false
        private fun expect(t: String) {
            if (!eat(t)) error("expected $t at ${peek()}")
        }

        fun program(topLevel: Boolean): Node {
            val (block, expression) = statements(end = "")
            if (i < toks.size) error("unexpected ${peek()}")
            return Program(block, expression && topLevel)
        }

        /** Statements up to [end] (a "}" or the end); also whether it was one expression with no ";". */
        private fun statements(end: String): Pair<Block, Boolean> {
            val list = ArrayList<Node>()
            var semis = 0
            var simple = true
            while (i < toks.size && peek() != end) {
                if (eat(";")) {
                    semis++
                    continue
                }
                val st = statement()
                if (st !is Expr) simple = false
                list += (st as? Expr)?.node ?: st
            }
            return Block(list, true) to (simple && semis == 0 && list.size == 1)
        }

        /** Marks a statement that is a plain expression. */
        private class Expr(val node: Node) : Node() {
            override fun eval(s: Scope, f: Flow) = node.eval(s, f)
        }

        private fun statement(): Node = when (peek()) {
            "return" -> {
                next()
                Return(if (peek() == ";" || peek() == "}" || peek() == "") null else expr())
            }
            "if" -> ifStatement()
            "loop" -> {
                next()
                expect("(")
                val n = expr()
                expect(",")
                val body = expr()
                expect(")")
                Loop(n, body)
            }
            "break" -> { next(); Jump(true) }
            "continue" -> { next(); Jump(false) }
            else -> Expr(expr())
        }

        private fun ifStatement(): Node {
            expect("if")
            expect("(")
            val c = expr()
            expect(")")
            val then = if (peek() == "{") block() else statement()
            val otherwise = if (eat("else")) (if (peek() == "if") ifStatement() else if (peek() == "{") block() else statement()) else null
            return If(c, then, otherwise)
        }

        private fun block(): Node {
            expect("{")
            val (b, _) = statements(end = "}")
            expect("}")
            return b
        }

        fun expr(): Node = assignment()

        private fun assignment(): Node {
            val left = ternary()
            if (peek() == "=" ) {
                next()
                val right = assignment()
                return if (left is Var) Assign(left, right) else right
            }
            return left
        }

        private fun ternary(): Node {
            val c = coalesce()
            if (eat("?")) {
                val a = ternary()
                val b = if (eat(":")) ternary() else null
                return Ternary(c, a, b)
            }
            return c
        }

        private fun coalesce(): Node {
            var a = or()
            while (eat("??")) a = Binary("??", a, or())
            return a
        }

        private fun or(): Node {
            var a = and()
            while (eat("||")) a = Binary("||", a, and())
            return a
        }

        private fun and(): Node {
            var a = equality()
            while (eat("&&")) a = Binary("&&", a, equality())
            return a
        }

        private fun equality(): Node {
            var a = compare()
            while (peek() == "==" || peek() == "!=") a = Binary(next(), a, compare())
            return a
        }

        private fun compare(): Node {
            var a = additive()
            while (peek() in setOf("<", "<=", ">", ">=")) a = Binary(next(), a, additive())
            return a
        }

        private fun additive(): Node {
            var a = multiplicative()
            while (peek() == "+" || peek() == "-") a = Binary(next(), a, multiplicative())
            return a
        }

        private fun multiplicative(): Node {
            var a = unary()
            while (peek() == "*" || peek() == "/" || peek() == "%") a = Binary(next(), a, unary())
            return a
        }

        private fun unary(): Node = when (peek()) {
            "-", "!", "+" -> Unary(next()[0], unary())
            else -> primary()
        }

        private fun primary(): Node {
            val t = next()
            if (t.isEmpty()) error("unexpected end")
            if (t == "(") {
                val e = expr()
                expect(")")
                return e
            }
            if (t == "{") {
                i--
                return block()
            }
            if (t.startsWith("'")) return Const(t.trim('\'').hashCode().toDouble())
            if (t[0].isDigit() || (t[0] == '.' && t.length > 1)) return Const(t.trimEnd('f', 'F').toDouble())
            if (t == "true") return Const(1.0)
            if (t == "false") return Const(0.0)
            val name = normalize(t)
            if (eat("(")) {
                val args = ArrayList<Node>()
                if (!eat(")")) {
                    do args += expr() while (eat(","))
                    expect(")")
                }
                return Call(name, args)
            }
            return variable(name)
        }

        private fun normalize(t: String): String {
            val l = t.lowercase()
            return when {
                l.startsWith("query.") -> "q." + l.removePrefix("query.")
                l.startsWith("variable.") -> "v." + l.removePrefix("variable.")
                l.startsWith("temp.") -> "t." + l.removePrefix("temp.")
                l.startsWith("context.") -> "c." + l.removePrefix("context.")
                l.startsWith("arg.") -> "a." + l.removePrefix("arg.")
                else -> l
            }
        }

        private fun variable(name: String): Var {
            val dot = name.indexOf('.')
            val ns = if (dot > 0) name.substring(0, dot) else ""
            val rest = if (dot > 0) name.substring(dot + 1) else name
            return when (ns) {
                "q" -> Var(Space.QUERY, rest)
                "v" -> Var(Space.VARIABLE, rest)
                "t" -> Var(Space.TEMP, rest)
                "c" -> Var(Space.CONTEXT, rest)
                "a" -> Var(Space.ARG, rest)
                "option", "o" -> Var(Space.OPTION, rest)
                else -> Var(Space.OTHER, name)
            }
        }

        companion object {
            private val TWO = setOf("&&", "||", "<=", ">=", "==", "!=", "??", "->")

            fun tokenize(s: String): List<String> {
                val out = ArrayList<String>()
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c.isWhitespace() -> i++
                        c == '/' && i + 1 < s.length && s[i + 1] == '/' -> while (i < s.length && s[i] != '\n') i++
                        c == '\'' -> {
                            val end = s.indexOf('\'', i + 1).let { if (it < 0) s.length - 1 else it }
                            out += s.substring(i, end + 1)
                            i = end + 1
                        }
                        c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                            val st = i
                            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                            if (i < s.length && (s[i] == 'f' || s[i] == 'F')) i++
                            out += s.substring(st, i)
                        }
                        c.isLetter() || c == '_' -> {
                            val st = i
                            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
                            out += s.substring(st, i)
                        }
                        else -> {
                            val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                            if (two in TWO) {
                                out += two
                                i += 2
                            } else {
                                out += c.toString()
                                i++
                            }
                        }
                    }
                }
                return out
            }
        }
    }
}
