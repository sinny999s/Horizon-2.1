package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostTasksEvent

/**
 * A task runs once its counter drops below zero. Tick tasks count player ticks: phase 10 at
 * PlayerUpdate priority 10, any other phase at priority −10. Frame tasks count milliseconds, once per frame after the
 * frame's queued tasks (PostTasksEvent). A task scheduled while a pass runs is first seen by the next pass.
 */
object Scheduler {
    private class Task(var left: Int, val phase: Int, val ticks: Boolean, val run: () -> Unit) {
        private var last = System.currentTimeMillis()

        /** Count down, and run when due (true = done). */
        fun step(): Boolean {
            left -= if (ticks) 1 else (System.currentTimeMillis() - last).toInt()
            if (left < 0) {
                run()
                return true
            }
            last = System.currentTimeMillis()
            return false
        }
    }

    private val tasks = ArrayList<Task>()
    private val parked = ArrayList<Task>()
    private var inPass = false

    fun init() {
        EventBus.on<PlayerUpdateEvent>(this, 10) { pass { it.ticks && it.phase == 10 } }
        EventBus.on<PlayerUpdateEvent>(this, -10) { pass { it.ticks && it.phase != 10 } }
        EventBus.on<PostTasksEvent>(this) { pass { !it.ticks } }
    }

    private inline fun pass(wanted: (Task) -> Boolean) {
        inPass = true
        try {
            val it = tasks.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (wanted(t) && t.step()) it.remove()
            }
        } finally {
            inPass = false
        }
        tasks += parked
        parked.clear()
    }

    /**
     * Runs [task] once more than [delay] passes went by ([ticks]) or more than [delay] ms (frame
     * task); tick tasks with [phase] 10 are counted at PlayerUpdate +10, others at −10.
     */
    fun schedule(delay: Int, phase: Int, ticks: Boolean, task: () -> Unit) {
        val t = Task(delay, phase, ticks, task)
        if (inPass) parked += t else tasks += t
    }

    /** Runs [task] [delay] + 1 player ticks from now (scheduled in tick N with delay 3, it runs in tick N + 4). */
    fun ticks(delay: Int, phase: Int = 10, task: () -> Unit) = schedule(delay, phase, true, task)

    /** A frame task: runs on the first frame more than [delay] ms after now. */
    fun ms(delay: Int, task: () -> Unit) = schedule(delay, 10, false, task)
}
