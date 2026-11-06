/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

/** Atomic state transforms; late worker callbacks cannot revive a stopped cast. */
internal class CastStateStore<T>(initial: T, private val active: (T) -> Boolean) {
    @Volatile var value = initial
        private set
    @Synchronized fun update(start: Boolean = false, transform: (T) -> T): T? {
        if (!start && !active(value)) return null
        return transform(value).also { value = it }
    }
}
