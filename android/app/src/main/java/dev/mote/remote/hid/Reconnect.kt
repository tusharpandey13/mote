package dev.mote.remote.hid

/**
 * Pure auto-reconnect policy. connect() on a host that isn't bonded makes Android start pairing
 * (a pairing dialog per attempt), so auto-connect only ever targets bonded hosts, pauses while
 * the phone is discoverable for pairing, and gives up after [MAX_ATTEMPTS] until something changes.
 */
object Reconnect {
    const val MAX_ATTEMPTS = 3

    /** Selected host if bonded, else the stack's plugged host if bonded, else nothing. */
    fun target(selected: String?, plugged: String?, bonded: Set<String>): String? =
        selected?.takeIf { it in bonded } ?: plugged?.takeIf { it in bonded }

    /** True when the saved host has been unpaired: show "Not paired" and never auto-connect to it. */
    fun unpaired(selected: String?, bonded: Set<String>): Boolean = selected != null && selected !in bonded

    /** Bonded addresses, or null when they can't be known (no permission, adapter off): keep the last verdict then. */
    fun bonded(env: BtEnv): Set<String>? =
        if (env.supported && env.permitted && env.enabled) env.hosts.mapTo(HashSet()) { it.address } else null

    fun allowed(attempts: Int, nowMs: Long, pausedUntilMs: Long): Boolean =
        attempts < MAX_ATTEMPTS && nowMs >= pausedUntilMs

    /** The attempt budget is spent: the UI says the host wasn't found instead of "searching" (until the user or the environment resets it). */
    fun exhausted(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS
}
