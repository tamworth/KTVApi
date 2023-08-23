package io.agora.ktvdemo.utils

object KeyCenter {

    const val LeadSingerUid = 1000
    const val CoSingerUid = 2000
    const val AudienceUid = 3000

    const val songCode: Long = 6805795303139450

    var localUid: Int = LeadSingerUid

    var channelId: String = ""

    var isMcc: Boolean = true

    fun isLeadSinger(): Boolean = localUid == LeadSingerUid
    fun isCoSinger(): Boolean = localUid == CoSingerUid
    fun isAudience(): Boolean = localUid != LeadSingerUid && localUid != CoSingerUid
}