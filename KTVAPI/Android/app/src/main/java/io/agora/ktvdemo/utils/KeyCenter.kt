package io.agora.ktvdemo.utils

object KeyCenter {

    const val LeadSingerUid = 10003213
    const val CoSingerUid = 2000
    const val AudienceUid = 3000

    const val songCode: Long = 40289835
    const val songCode2: Long = 89488966

    var localUid: Int = LeadSingerUid

    var channelId: String = ""

    var isMcc: Boolean = true

    fun isLeadSinger(): Boolean = localUid == LeadSingerUid
    fun isCoSinger(): Boolean = localUid == CoSingerUid
    fun isAudience(): Boolean = localUid != LeadSingerUid && localUid != CoSingerUid
}