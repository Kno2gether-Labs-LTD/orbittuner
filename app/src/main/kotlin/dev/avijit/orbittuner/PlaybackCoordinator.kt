package dev.avijit.orbittuner

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Only one thing plays at a time (metronome or backing track): a player calls [starting] before it starts, and every
 * other registered player is stopped. The stop is requested at once, but the other player's audio can tail for a
 * few tens of milliseconds while its buffer drains.
 */
class PlaybackCoordinator {
    private class Player(val owner: Any, val stop: () -> Unit)

    private val players = CopyOnWriteArrayList<Player>()

    fun register(owner: Any, stop: () -> Unit) {
        players += Player(owner, stop)
    }

    fun starting(owner: Any) {
        for (p in players) if (p.owner != owner) p.stop()
    }
}
