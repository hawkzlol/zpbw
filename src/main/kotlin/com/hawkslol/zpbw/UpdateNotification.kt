package com.hawkslol.zpbw

/** Presentation timing only. Network completion never touches the player or chat. */
class UpdateNotification {
    private val notice = FirstInstallNotice()

    fun poll(result: UpdateCheckResult?, welcomePending: Boolean, session: Any?, inSkyblock: Boolean,
             nowNanos: Long): UpdateCheckResult? {
        if (welcomePending) {
            notice.reset()
            return null
        }
        val available = result?.status == UpdateCheckStatus.AVAILABLE && result.version != null && result.url != null
        return if (notice.poll(available, session, inSkyblock, nowNanos)) result else null
    }

    fun reset() = notice.reset()
}
