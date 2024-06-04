package io.agora.ktvapi

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.agora.mccex.IMusicContentCenterEx
import io.agora.mccex.IMusicContentCenterExEventHandler
import io.agora.mccex.IMusicContentCenterExScoreEventHandler
import io.agora.mccex.IMusicPlayer
import io.agora.mccex.constants.LyricType
import io.agora.mccex.constants.MccExState
import io.agora.mccex.constants.MccExStateReason
import io.agora.mccex.model.LineScoreData
import io.agora.mccex.model.RawScoreData
import io.agora.mccex.model.YsdVendorConfigure
// TODO
//import com.tuwan.android.uitl.mmkv.MmkvDataUtils
//import com.tuwan.twmusic.KTVSingRole
//import com.tuwan.twmusic.TwILrcView
import io.agora.mediaplayer.Constants
import io.agora.mediaplayer.Constants.MediaPlayerState
import io.agora.mediaplayer.IMediaPlayer
import io.agora.mediaplayer.IMediaPlayerObserver
import io.agora.mediaplayer.data.PlayerUpdatedInfo
import io.agora.mediaplayer.data.SrcInfo
import io.agora.musiccontentcenter.IAgoraMusicContentCenter
import io.agora.musiccontentcenter.Music
import io.agora.musiccontentcenter.MusicChartInfo
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants.AUDIO_SCENARIO_CHORUS
import io.agora.rtc2.Constants.AUDIO_SCENARIO_GAME_STREAMING
import io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER
import io.agora.rtc2.DataStreamConfig
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcConnection
import io.agora.rtc2.RtcEngineEx
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class GiantChorusKTVApiImpl : KTVApi, IMediaPlayerObserver,
    IMusicContentCenterExEventHandler, IMusicContentCenterExScoreEventHandler,
    IRtcEngineEventHandler() {
    private val TAG: String = "KTV_API_LOG"

    // 外部可修改
    var songMode: KTVMusicType = KTVMusicType.SONG_CODE
    var useCustomAudioSource: Boolean = false

    // 音频最佳实践
    var remoteVolume: Int = 40 // 远端音频
    var mpkPlayoutVolume: Int = 80
    var mpkPublishVolume: Int = 80

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var mRtcEngine: RtcEngineEx
    private lateinit var mMusicCenter: IMusicContentCenterEx
    private lateinit var mPlayer: IMusicPlayer

    private lateinit var ktvApiConfig: KTVApiConfig
    private var innerDataStreamId: Int = 0
    private var innerAudienceChannelStreamId: Int = 0
    private var subChorusConnection: RtcConnection? = null
    private var mpkConnection: RtcConnection? = null

    private var mainSingerUid: Int = -1
    private var songCode: Long = 0
    private var songUrl: String = ""
    private var songIdentifier: String = ""
    private var forward: Boolean = false    //使用worder的同步

    private val lyricCallbackMap =
        mutableMapOf<String, (songNo: Long, lyricUrl: String?) -> Unit>() // (requestId, callback)
    private val lyricSongCodeMap = mutableMapOf<String, Long>() // (requestId, songCode)
    private val startScoreMap = mutableMapOf<String, (songCode: Long, status: MccExState, msg: MccExStateReason) -> Unit>() // (songNo, callback)
    private val loadMusicCallbackMap =
        mutableMapOf<String, (
            songCode: Long,
            percent: Int,
            status: MccExState,
            msg: String?,
            lyricUrl: String?
        ) -> Unit>() // (songNo, callback)
    private val musicChartsCallbackMap =
        mutableMapOf<String, (requestId: String?, errorCode: Int, list: Array<out MusicChartInfo>?) -> Unit>()
    private val musicCollectionCallbackMap =
        mutableMapOf<String, (requestId: String?, errorCode: Int, page: Int, pageSize: Int, total: Int, list: Array<out Music>?) -> Unit>()

    private var lrcView: ILrcView? = null // TODO modify

    private var localPlayerPosition: Long = 0
    private var localPlayerSystemTime: Long = 0

    //歌词实时刷新
    private var mStopDisplayLrc = true
    private var mReceivedPlayPosition: Long = 0 //播放器播放position，ms
    private var mLastReceivedPlayPosTime: Long = 0L
    private var mLastSetPlayPosTime: Long = 0   //上次设置给歌词组件的pos

    // event
    private var ktvApiEventHandlerList = mutableListOf<IKTVApiEventHandler>()
    private var mainSingerHasJoinChannelEx: Boolean = false

    // 合唱校准
    private var audioPlayoutDelay = 0

    // 音高
    private var pitch = 0.0

    // 是否在麦上
    private var isOnMicOpen = false
    private var isRelease = false

    // mpk状态
    private var mediaPlayerState: MediaPlayerState = MediaPlayerState.PLAYER_STATE_IDLE

    // 音频路由
    private var audioRoute = -1

    // 演唱分数
    private var singingScore = 0

    // ktv的场景，0:49厅大合唱，伴唱角色用自己的pitch，1:KTV小厅用主持的pitch
    private var ktvScene = 0

    // 歌词信息是否来源于 dataStream
    private var recvFromDataStream = false

    companion object {
        private val scheduledThreadPool: ScheduledExecutorService =
            Executors.newScheduledThreadPool(5)
    }

    override fun initialize(
        config: KTVApiConfig
    ) {
        this.mRtcEngine = config.engine as RtcEngineEx
        this.ktvApiConfig = config
        this.ktvScene = config.ktvScene

        // ------------------ 初始化内容中心 ------------------
//        val contentCenterConfiguration = MusicContentCenterExConfiguration()
//        contentCenterConfiguration.context = config.context
//        contentCenterConfiguration.eventHandler = this
//        contentCenterConfiguration.vendorConfigure = YsdVendorConfigure(
//            appId = "203277",
//            appKey = "ac8e699bb7c94de893a67fa7de59212f",
//            token = "KNH5KJVBH2UML9GFB5O20HKGP47MOTU5LA5VT431JD0KG0NISDVM5CHJ6NCUTR13LRICSE897SBJQL2VQO9BTRKKAEN98489BI64BC6Q6JDAOHT2N01G4UG765O51JCN2JPKA0DD1TE3SQ4GGDINCNE7PC93409MDCSQSLAS2BVGUCBJS2711DLF04Q7VU9Q",
//            userId = "4AB8152067F0622F5A225CFCC4E282BD",
//            deviceId = "2323",
//            chargeMode = ChargeMode.ONCE,
//            urlTokenExpireTime = 60 * 15
//        )
//        contentCenterConfiguration.scoreEventHandler = this
//        contentCenterConfiguration.enableLog = true
//        contentCenterConfiguration.enableSaveLogToFile = true
//
//        mMusicCenter = IMusicContentCenterEx.create(mRtcEngine)!!
//
//        mMusicCenter.initialize(contentCenterConfiguration)

        mMusicCenter = config.mMusicCenter
        mMusicCenter.registerEventHandler(this)
        mMusicCenter.registerScoreEventHandler(this)

        // ------------------ 初始化音乐播放器实例 ------------------
        mPlayer = mMusicCenter.createMusicPlayer()!!
        mPlayer.adjustPublishSignalVolume(mpkPublishVolume)
        mPlayer.adjustPlayoutVolume(mpkPlayoutVolume)

        // 注册回调
        mRtcEngine.addHandler(this)
        mPlayer.registerPlayerObserver(this)
        mMusicCenter.registerEventHandler(this)

        renewInnerDataStreamId()    //初始化innerDataStreamId
        if (config.type == KTVType.Cantata) {
            setKTVParameters()    //合唱参数设置
            startDisplayLrc()     //歌词同步
            startSyncPitch()      //音高同步

        }
        isRelease = false
    }

    override fun renewInnerDataStreamId() {
        val innerCfg = DataStreamConfig()
        innerCfg.syncWithAudio = true
        innerCfg.ordered = false
        this.innerDataStreamId = mRtcEngine.createDataStream(innerCfg)
    }

    //用于合唱对齐的参数
    private fun setKTVParameters() {
//        mRtcEngine.setParameters("{\"rtc.enable_nasa2\": false}")
        mRtcEngine.setParameters("{\"rtc.use_audio4\": true}")
        mRtcEngine.setParameters("{\"rtc.ntp_delay_drop_threshold\":1000}")
        mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp\": true}")
        mRtcEngine.setParameters("{\"rtc.net.maxS2LDelay\": 800}")
        mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")

        mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
        mRtcEngine.setParameters("{\"che.audio.neteq.targetlevel_offset\": 20}")

        mRtcEngine.setParameters("{\"rtc.net.maxS2LDelayBroadcast\":400}")
        mRtcEngine.setParameters("{\"che.audio.neteq.prebuffer\":true}")
        mRtcEngine.setParameters("{\"che.audio.neteq.prebuffer_max_delay\":600}")
        mRtcEngine.setParameters("{\"che.audio.max_mixed_participants\": 8}")
        mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 48000}")
        mRtcEngine.setParameters("{\"che.audio.direct.uplink_process\": false}")
        mRtcEngine.setParameters("{\"che.audio.uplink_apm_async_process\": true}")

        // Android Only
        // TODO modify
//        val isOpenDeviceDelay = MmkvDataUtils.getInstance().readBoolean("isOpenDeviceDelay", false)
//        if (isOpenDeviceDelay) {
//            mRtcEngine.setParameters("{\"che.audio.enable_estimated_device_delay\":false}")
//        }
    }

    override fun addEventHandler(ktvApiEventHandler: IKTVApiEventHandler) {
        ktvApiEventHandlerList.add(ktvApiEventHandler)
    }

    override fun upateRoomOwner(isRoomOwner: Boolean) {
        if (this::ktvApiConfig.isInitialized) {
            ktvApiConfig.isRoomOwner = isRoomOwner
        }
    }

    override fun removeEventHandler(ktvApiEventHandler: IKTVApiEventHandler) {
        ktvApiEventHandlerList.remove(ktvApiEventHandler)
    }

    override fun release() {
        if (isRelease) return
        isRelease = true
        singerRole = KTVSingRole.Audience
        duration = 0

        stopSyncPitch()
        stopDisplayLrc()
        stopSyncCloudConvergenceStatus()
        stopSyncScore()
        this.mLastReceivedPlayPosTime = 0
        this.mReceivedPlayPosition = 0
        this.innerDataStreamId = 0
        this.singingScore = 0
        this.mainSingerUid = -1

        lyricCallbackMap.clear()
        loadMusicCallbackMap.clear()
        musicChartsCallbackMap.clear()
        musicCollectionCallbackMap.clear()
        lrcView = null

        mRtcEngine.removeHandler(this)
        mPlayer.unRegisterPlayerObserver(this)
        mMusicCenter.unregisterEventHandler()

//        mPlayer.stop()
        mPlayer.destroy()
        IAgoraMusicContentCenter.destroy()

        mainSingerHasJoinChannelEx = false
    }

    override fun renewToken(rtmToken: String, chorusChannelRtcToken: String) {
        // 更新RtmToken
        mMusicCenter.renewToken(rtmToken)
        // 更新合唱频道RtcToken
//        if (subChorusConnection != null) {
//            val channelMediaOption = ChannelMediaOptions()
//            channelMediaOption.token = chorusChannelRtcToken
//            mRtcEngine.updateChannelMediaOptionsEx(channelMediaOption, subChorusConnection)
//        }
    }

    // 1、Audience -》SoloSinger
    // 2、Audience -》LeadSinger
    // 3、SoloSinger -》Audience
    // 4、Audience -》CoSinger
    // 5、CoSinger -》Audience
    // 6、SoloSinger -》LeadSinger
    // 7、LeadSinger -》SoloSinger
    // 8、LeadSinger -》Audience
    var singerRole: KTVSingRole = KTVSingRole.Audience
    override fun switchSingerRole(
        newRole: KTVSingRole,
        switchRoleStateListener: ISwitchRoleStateListener?
    ) {
        Log.d(TAG, "switchSingerRole oldRole: $singerRole, newRole: $newRole")
        val oldRole = singerRole
        if (this.singerRole == KTVSingRole.Audience && newRole == KTVSingRole.SoloSinger) {
            // 1、Audience -》SoloSinger
            this.singerRole = newRole
            becomeSoloSinger()
            ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
            switchRoleStateListener?.onSwitchRoleSuccess()
        } else if (this.singerRole == KTVSingRole.SoloSinger && newRole == KTVSingRole.Audience) {
            // 3、SoloSinger -》Audience

            stopSing()
            this.singerRole = newRole
            ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
            switchRoleStateListener?.onSwitchRoleSuccess()

        } else if (this.singerRole == KTVSingRole.Audience && newRole == KTVSingRole.LeadSinger) {//观众变成主持人
            // 2、Audience -》LeadSinger
            if (ktvApiConfig.type == KTVType.Cantata) {
                joinChorus2(newRole, switchRoleStateListener)
                singerRole = newRole
                ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
                switchRoleStateListener?.onSwitchRoleSuccess()
            } else {
//                joinChorus(newRole, ktvApiConfig.chorusChannelToken, object :
//                    OnJoinChorusStateListener {
//                    override fun onJoinChorusSuccess() {
//                        Log.d(TAG, "onJoinChorusSuccess")
//                        singerRole = newRole
//                        ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
//                        switchRoleStateListener?.onSwitchRoleSuccess()
//                    }
//
//                    override fun onJoinChorusFail(reason: KTVJoinChorusFailReason) {
//                        Log.d(TAG, "onJoinChorusFail reason：$reason")
//                        leaveChorus(newRole)
//                        switchRoleStateListener?.onSwitchRoleFail(SwitchRoleFailReason.JOIN_CHANNEL_FAIL)
//                    }
//                })
            }
        } else if (this.singerRole == KTVSingRole.Audience && newRole == KTVSingRole.CoSinger) {//观众变成选手
            // 4、Audience -》CoSinger
            if (ktvApiConfig.type == KTVType.Cantata) {
                joinChorus2(newRole, switchRoleStateListener)
                singerRole = newRole
                ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
                switchRoleStateListener?.onSwitchRoleSuccess()
            } else {
//                joinChorus(newRole, ktvApiConfig.chorusChannelToken, object :
//                    OnJoinChorusStateListener {
//                    override fun onJoinChorusSuccess() {
//                        Log.d(TAG, "onJoinChorusSuccess")
//                        singerRole = newRole
//                        ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
//                        switchRoleStateListener?.onSwitchRoleSuccess()
//                    }
//
//                    override fun onJoinChorusFail(reason: KTVJoinChorusFailReason) {
//                        Log.d(TAG, "onJoinChorusFail reason：$reason")
//                        leaveChorus(newRole)
//                        switchRoleStateListener?.onSwitchRoleFail(SwitchRoleFailReason.JOIN_CHANNEL_FAIL)
//                    }
//                })
            }
        } else if (this.singerRole == KTVSingRole.CoSinger && newRole == KTVSingRole.Audience) {//选手变成观众
            // 5、CoSinger -》Audience
            if (ktvApiConfig.type == KTVType.Cantata) {
                leaveChorus2(singerRole)
            }

            this.singerRole = newRole
            ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
            switchRoleStateListener?.onSwitchRoleSuccess()

        } else if (this.singerRole == KTVSingRole.CoSinger && newRole == KTVSingRole.LeadSinger) {
            // 6、CoSinger -》LeadSinger

            if (ktvApiConfig.type == KTVType.Cantata) {
                joinChorus2(newRole, switchRoleStateListener)
                singerRole = newRole
                ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
                switchRoleStateListener?.onSwitchRoleSuccess()
            } else {
//                joinChorus(newRole, ktvApiConfig.chorusChannelToken, object :
//                    OnJoinChorusStateListener {
//                    override fun onJoinChorusSuccess() {
//                        Log.d(TAG, "onJoinChorusSuccess")
//                        singerRole = newRole
//                        ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
//                        switchRoleStateListener?.onSwitchRoleSuccess()
//                    }
//
//                    override fun onJoinChorusFail(reason: KTVJoinChorusFailReason) {
//                        Log.d(TAG, "onJoinChorusFail reason：$reason")
//                        leaveChorus(newRole)
//                        switchRoleStateListener?.onSwitchRoleFail(SwitchRoleFailReason.JOIN_CHANNEL_FAIL)
//                    }
//                })
            }
        } else if (this.singerRole == KTVSingRole.LeadSinger && newRole == KTVSingRole.CoSinger) {
            // 7、LeadSinger -》CoSinger

            if (ktvApiConfig.type == KTVType.Cantata) {
                leaveChorus2(singerRole)
                singerRole = newRole
                //停止播放
                mPlayer.stop()
                mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")
                mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
                mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 48000}")
            } else {

            }
//
//            this.singerRole = newRole
//            ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
//            switchRoleStateListener?.onSwitchRoleSuccess()
        } else if (this.singerRole == KTVSingRole.LeadSinger && newRole == KTVSingRole.Audience) {//主持变成观众
            // 8、LeadSinger -》Audience
            if (ktvApiConfig.type == KTVType.Cantata) {
                leaveChorus2(singerRole)
            } else {
//                leaveChorus(singerRole)
            }
            stopSing()

            this.singerRole = newRole
            ktvApiEventHandlerList.forEach { it.onSingerRoleChanged(oldRole, newRole) }
            switchRoleStateListener?.onSwitchRoleSuccess()
        } else {
            switchRoleStateListener?.onSwitchRoleFail(SwitchRoleFailReason.NO_PERMISSION)
            Log.e(TAG, "Error！You can not switch role from $singerRole to $newRole!")
        }
    }

    override fun loadMusic(
        songCode: Long,
        config: KTVLoadMusicConfiguration,
        musicLoadStateListener: IMusicLoadStateListener
    ) {
        Log.d(TAG, "loadMusic called: songCode $songCode")
        // 设置到全局， 连续调用以最新的为准
        this.songMode = KTVMusicType.SONG_CODE

        val code = mMusicCenter.getInternalSongCode(songCode.toString(), null)
        // 设置到全局， 连续调用以最新的为准
        this.songCode = code

        this.songIdentifier = config.songIdentifier
        this.mainSingerUid = config.mainSingerUid
        mLastReceivedPlayPosTime = 0
        mReceivedPlayPosition = 0

        if (config.mode == KTVLoadMusicMode.LOAD_NONE) {
            return
        }

        if (config.mode == KTVLoadMusicMode.LOAD_LRC_ONLY) {
            // 只加载歌词
            loadLyric(code) { song, lyricUrl ->
                if (this.songCode != song) {
                    // 当前歌曲已发生变化，以最新load歌曲为准
                    Log.e(TAG, "loadMusic failed: CANCELED")
                    musicLoadStateListener.onMusicLoadFail(song, KTVLoadSongFailReason.CANCELED)
                    return@loadLyric
                }

                if (lyricUrl == null) {
                    // 加载歌词失败
                    Log.e(TAG, "loadMusic failed: NO_LYRIC_URL")
                    musicLoadStateListener.onMusicLoadFail(song, KTVLoadSongFailReason.NO_LYRIC_URL)
                } else {
                    // 加载歌词成功
                    Log.d(TAG, "loadMusic success")
                    lrcView?.onDownloadLrcData(lyricUrl)
                    musicLoadStateListener.onMusicLoadSuccess(song, lyricUrl)
                }
            }
            return
        }

        // 预加载歌曲
        preLoadMusic(code) { song, percent, status, msg, lrcUrl ->
            if (status == MccExState.PRELOAD_STATE_COMPLETED) {
                // 预加载歌曲成功
                if (this.songCode != song) {
                    // 当前歌曲已发生变化，以最新load歌曲为准
                    Log.e(TAG, "loadMusic failed: CANCELED")
                    musicLoadStateListener.onMusicLoadFail(song, KTVLoadSongFailReason.CANCELED)
                    return@preLoadMusic
                }
                if (config.mode == KTVLoadMusicMode.LOAD_MUSIC_AND_LRC) {
                    // 需要加载歌词
                    loadLyric(song) { _, lyricUrl ->
                        if (this.songCode != song) {
                            // 当前歌曲已发生变化，以最新load歌曲为准
                            Log.e(TAG, "loadMusic failed: CANCELED")
                            musicLoadStateListener.onMusicLoadFail(
                                song,
                                KTVLoadSongFailReason.CANCELED
                            )
                            return@loadLyric
                        }

                        if (lyricUrl == null) {
                            // 加载歌词失败
                            Log.e(TAG, "loadMusic failed: NO_LYRIC_URL")
                            musicLoadStateListener.onMusicLoadFail(
                                song,
                                KTVLoadSongFailReason.NO_LYRIC_URL
                            )
                        } else {
                            // 加载歌词成功
                            Log.d(TAG, "loadMusic success")
                            lrcView?.onDownloadLrcData(lyricUrl)
                            musicLoadStateListener.onMusicLoadProgress(
                                song,
                                100,
                                MusicLoadStatus.COMPLETED,
                                msg,
                                lrcUrl
                            )
                            musicLoadStateListener.onMusicLoadSuccess(song, lyricUrl)
                        }

                        if (config.autoPlay) {
                            // 主唱自动播放歌曲
                            if (ktvApiConfig.type == KTVType.Normal)
                                switchSingerRole(KTVSingRole.SoloSinger, null)
                            startSing(song, 0)
                        }
                    }
                } else if (config.mode == KTVLoadMusicMode.LOAD_MUSIC_ONLY) {
                    // 不需要加载歌词
                    Log.d(TAG, "loadMusic success")
                    if (config.autoPlay) {
                        // 主唱自动播放歌曲
                        if (ktvApiConfig.type == KTVType.Normal)
                            switchSingerRole(KTVSingRole.SoloSinger, null)
                        startSing(song, 0)
                    }
                    musicLoadStateListener.onMusicLoadProgress(
                        song,
                        100,
                        MusicLoadStatus.COMPLETED,
                        msg,
                        lrcUrl
                    )
                    musicLoadStateListener.onMusicLoadSuccess(song, "")
                }
            } else if (status == MccExState.PRELOAD_STATE_PRELOADING) {
                // 预加载歌曲加载中
                musicLoadStateListener.onMusicLoadProgress(
                    song,
                    percent,
                    MusicLoadStatus.INPROGRESS,
                    msg,
                    lrcUrl)
            } else {
                // 预加载歌曲失败
                Log.e(TAG, "loadMusic failed: MUSIC_PRELOAD_FAIL:${status}")
                musicLoadStateListener.onMusicLoadFail(
                    song, KTVLoadSongFailReason.MUSIC_PRELOAD_FAIL
                )
            }
        }
    }

    override fun loadMusic(
        url: String,
        config: KTVLoadMusicConfiguration
    ) {
        Log.d(TAG, "loadMusic called2: songCode $songCode")
        this.songMode = KTVMusicType.SONG_URL
        this.songIdentifier = config.songIdentifier
        this.songUrl = url
        this.mainSingerUid = config.mainSingerUid
        this.mLastReceivedPlayPosTime = 0
        this.mReceivedPlayPosition = 0
        this.mLastSetPlayPosTime = 0

        if (config.autoPlay) {
            // 主唱自动播放歌曲
            switchSingerRole(KTVSingRole.SoloSinger, null)
            startSing(url, 0)
        }
    }

    override fun startScore(songCode: Long, onStartScoreCallback: (songCode: Long, status: MccExState, msg: MccExStateReason) -> Unit) {
        val code = mMusicCenter.getInternalSongCode(songCode.toString(), null)
        val ret = mMusicCenter.startScore(code)
        if (ret < 0) {
            onStartScoreCallback.invoke(songCode, MccExState.START_SCORE_STATE_FAIL, MccExStateReason.STATE_REASON_ERROR)
        } else {
            startScoreMap[code.toString()] = onStartScoreCallback
        }
    }

    override fun startSing(songCode: Long, startPos: Long) {
        Log.d(TAG, "playSong called: $singerRole")
        if (ktvApiConfig.type == KTVType.Cantata) {
            if (this.songCode != songCode) {
                Log.e(TAG, "startSing failed: canceled")
                return
            }
        }
//        mRtcEngine.adjustPlaybackSignalVolume(remoteVolume)
        mPlayer.open(songCode, startPos)
    }

    override fun startSing(url: String, startPos: Long) {
        Log.d(TAG, "playSong called: $singerRole")
        if (ktvApiConfig.type == KTVType.Cantata) {
            if (this.songUrl != url) {
                Log.e(TAG, "startSing failed: canceled")
                return
            }
        }
//        mRtcEngine.adjustPlaybackSignalVolume(remoteVolume)
        mPlayer.open(url, startPos)
    }

    override fun resumeSing() {
        Log.d(TAG, "resumePlay called")
        mPlayer.resume()
    }

    override fun pauseSing() {
        Log.d(TAG, "pausePlay called")
        mPlayer.pause()
    }

    override fun seekSing(time: Long) {
        Log.d(TAG, "seek called")
        mPlayer.seek(time)
        syncPlayProgress(time)
    }

    override fun setLrcView(view: ILrcView?) { // TODO modify
        Log.d(TAG, "setLrcView called")
        this.lrcView = view
    }

    override fun setLrcProgress(data: ByteArray?) {
        val jsonMsg: JSONObject
        val messageData = data ?: return
        try {
            val strMsg = String(messageData)
            jsonMsg = JSONObject(strMsg)
            if (!jsonMsg.has("cmd")) return
            if (jsonMsg.getString("cmd") == "setLrcTime") { // 同步歌词
                val realPosition = jsonMsg.getLong("realTime")
                val songId = jsonMsg.getString("songIdentifier")

                // 观众
                if (this.songIdentifier == songId) {
                    mLastReceivedPlayPosTime = System.currentTimeMillis()
                    mReceivedPlayPosition = realPosition
                } else {
                    mLastReceivedPlayPosTime = 0
                    mReceivedPlayPosition = 0
                }
            }
        } catch (exp: JSONException) {
            Log.e(TAG, "onStreamMessage:$exp")
        }
    }

    override fun setAudioMetadata(data: ByteArray?) {
        val messageData = data ?: return
        val lrcTime = LrcTimeOuterClass.LrcTime.parseFrom(messageData)
        if (lrcTime.type == LrcTimeOuterClass.MsgType.LRC_TIME) { //同步歌词
            val realPosition = lrcTime.ts
            val songId = lrcTime.songId
            val curTs = if (this.songIdentifier == songId) realPosition else 0
            updateLrcViewTime(curTs, false, true)
        }
    }

    override fun setMicStatus(isOnMicOpen: Boolean) {
        this.isOnMicOpen = isOnMicOpen
    }

    override fun setAudioPlayoutDelay(audioPlayoutDelay: Int) {
        this.audioPlayoutDelay = audioPlayoutDelay
    }

    override fun setSingingScore(score: Int) {
        this.singingScore = score
    }

    override fun getMediaPlayer(): IMediaPlayer {
        return mPlayer
    }

    // ------------------ inner KTVApi --------------------
    private fun becomeSoloSinger() {
        Log.d(TAG, "becomeSoloSinger called")
        // 主唱进入合唱模式
        mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":false}")
        mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":false}")
        mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 80000}")
        mRtcEngine.setAudioScenario(AUDIO_SCENARIO_CHORUS)

        val channelMediaOption = ChannelMediaOptions()
        channelMediaOption.autoSubscribeAudio = true
        channelMediaOption.publishMediaPlayerId = mPlayer.mediaPlayerId
        channelMediaOption.publishMediaPlayerAudioTrack = true
        mRtcEngine.updateChannelMediaOptions(channelMediaOption)
    }

//    private fun joinChorus(
//        newRole: KTVSingRole,
//        token: String,
//        onJoinChorusStateListener: OnJoinChorusStateListener
//    ) {
//        Log.d(TAG, "joinChorus: $newRole")
//        when (newRole) {
//            KTVSingRole.LeadSinger -> {
//                joinChorus2ndChannel(newRole, token, mainSingerUid) { joinStatus ->
//                    if (joinStatus == 0) {
//                        onJoinChorusStateListener.onJoinChorusSuccess()
//                    } else {
//                        onJoinChorusStateListener.onJoinChorusFail(KTVJoinChorusFailReason.JOIN_CHANNEL_FAIL)
//                    }
//                }
//            }
//
//            KTVSingRole.CoSinger -> {
//                val channelMediaOption = ChannelMediaOptions()
//                channelMediaOption.autoSubscribeAudio = true
//                channelMediaOption.publishMediaPlayerAudioTrack = false
//                mRtcEngine.updateChannelMediaOptions(channelMediaOption)
//
//                // 预加载歌曲成功
//                if (songMode == KTVSongMode.SONG_CODE) {
//                    mPlayer.open(songCode, 0) // TODO open failed
//                } else {
//                    mPlayer.open(songUrl, 0) // TODO open failed
//                }
//
//                // 预加载成功后加入第二频道：预加载时间>>joinChannel时间
//                joinChorus2ndChannel(newRole, token, mainSingerUid) { joinStatus ->
//                    if (joinStatus == 0) {
//                        // 加入第二频道成功
//                        onJoinChorusStateListener.onJoinChorusSuccess()
//                    } else {
//                        // 加入第二频道失败
//                        onJoinChorusStateListener.onJoinChorusFail(KTVJoinChorusFailReason.JOIN_CHANNEL_FAIL)
//                    }
//                }
//            }
//
//            else -> {
//                Log.e(TAG, "JoinChorus with Wrong role: $singerRole")
//            }
//        }
//    }

//    private fun leaveChorus(role: KTVSingRole) {
//        Log.d(TAG, "leaveChorus: $singerRole")
//        when (role) {
//            KTVSingRole.LeadSinger -> {
//                leaveChorus2ndChannel(role)
//            }
//
//            KTVSingRole.CoSinger -> {
//                mPlayer.stop()
//                val channelMediaOption = ChannelMediaOptions()
//                channelMediaOption.autoSubscribeAudio = true
//                channelMediaOption.publishMediaPlayerAudioTrack = false
//                mRtcEngine.updateChannelMediaOptions(channelMediaOption)
//                leaveChorus2ndChannel(role)
//                mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")
//                mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
//                mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 48000}")
//                mRtcEngine.setAudioScenario(AUDIO_SCENARIO_GAME_STREAMING)
//            }
//
//            else -> {
//                Log.e(TAG, "JoinChorus with wrong role: $singerRole")
//            }
//        }
//    }

    private fun joinChorus2(
        newRole: KTVSingRole, switchRoleStateListener: ISwitchRoleStateListener?
    ) {
        Log.d(TAG, "joinChorus: $newRole")
        startSyncScore()
        when (newRole) {
            KTVSingRole.LeadSinger -> {
                mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":false}")
                mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":false}")
                mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 80000}")
                mRtcEngine.setAudioScenario(AUDIO_SCENARIO_CHORUS)

                mPlayer.setPlayerOption("play_pos_change_callback", 100)

                startSyncCloudConvergenceStatus()

                // mpk流加入频道,推伴奏，不推人声
                val options = ChannelMediaOptions()
                options.autoSubscribeAudio = false
                options.autoSubscribeVideo = false
                options.publishMicrophoneTrack = false
                options.publishMediaPlayerAudioTrack = true
                options.publishMediaPlayerId = mPlayer.mediaPlayerId
                options.clientRoleType = CLIENT_ROLE_BROADCASTER
                options.enableAudioRecordingOrPlayout = false

                val rtcConnection = RtcConnection()
                rtcConnection.channelId = ktvApiConfig.channelName
                rtcConnection.localUid = ktvApiConfig.chorusUid

                mpkConnection = rtcConnection
                mRtcEngine.joinChannelEx(
                    ktvApiConfig.mpkChannelToken,
                    rtcConnection,
                    options,
                    object : IRtcEngineEventHandler() {
                        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
                            Log.d(TAG, "onMPKJoinChannelSuccess, channel: $channel, uid: $uid")
                            switchRoleStateListener?.onJoinChorusChannelSuccess(channel, uid)
                        }

                        override fun onConnectionStateChanged(state: Int, reason: Int) {
                            super.onConnectionStateChanged(state, reason)
                            Log.d(TAG, "onConnectionStateChanged, state: $state, reason:$reason")
                        }

                        override fun onLeaveChannel(stats: RtcStats) {
                            Log.d(TAG, "onMPKLeaveChannel")
                        }

                        override fun onError(err: Int) {
                            super.onError(err)
                            Log.d(TAG, "onMPKJoinChannelError, channel: $err")
                        }
                    })

//                // 加入观众频道
//                val options2 = ChannelMediaOptions()
//                options2.publishMicrophoneTrack = false
//                options2.clientRoleType = CLIENT_ROLE_BROADCASTER
//                options2.enableAudioRecordingOrPlayout = false
//
//                val rtcConnection2 = RtcConnection()
//                rtcConnection2.channelId = ktvApiConfig.chorusChannelName
//                rtcConnection2.localUid = ktvApiConfig.localUid
//                subChorusConnection = rtcConnection2
//
//                mRtcEngine.joinChannelEx(
//                    ktvApiConfig.chorusChannelToken,
//                    rtcConnection2,
//                    options2,
//                    object : IRtcEngineEventHandler() {
//                        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
//                            Log.d(
//                                TAG,
//                                "onJoinAudienceChannelSuccess2, channel: $channel, uid: $uid"
//                            )
//                            innerAudienceChannelStreamId =
//                                mRtcEngine.createDataStreamEx(true, false, rtcConnection2)
//                        }
//
//                        override fun onLeaveChannel(stats: RtcStats) {
//                            Log.d(TAG, "onLeaveAudienceChannel")
//                        }
//                    })
            }

            KTVSingRole.CoSinger -> {
                mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":false}")
                mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":false}")
                mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 48000}")
                mRtcEngine.setAudioScenario(AUDIO_SCENARIO_CHORUS)

                // 预加载歌曲成功
                mPlayer.open(songCode, 0)
                mPlayer.setPlayerOption("play_pos_change_callback", 100)
                mRtcEngine.muteRemoteAudioStream(ktvApiConfig.chorusUid, true)
            }

            else -> {
                Log.e(TAG, "JoinChorus with Wrong role: $singerRole")
            }
        }
    }

    private fun leaveChorus2(role: KTVSingRole) {
        Log.d(TAG, "leaveChorus: $singerRole")
        stopSyncScore()
        when (role) {
            KTVSingRole.LeadSinger -> {
                stopSyncCloudConvergenceStatus()
                mainSingerHasJoinChannelEx = false
                mRtcEngine.leaveChannelEx(mpkConnection)
//                mRtcEngine.leaveChannelEx(subChorusConnection)
            }

            KTVSingRole.CoSinger -> {
                mPlayer.stop()
                mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")
                mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
                mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 48000}")
                mRtcEngine.setAudioScenario(AUDIO_SCENARIO_GAME_STREAMING)
            }

            else -> {
                Log.e(TAG, "JoinChorus with wrong role: $singerRole")
            }
        }
        //重置pitch
        pitch = 0.0
    }

    fun stopSing() {
        Log.d(TAG, "stopSong called")

        mPlayer.stop()
        if (singerRole == KTVSingRole.Audience) {
            mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")
            mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
            mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 48000}")
            mRtcEngine.setAudioScenario(AUDIO_SCENARIO_GAME_STREAMING)
        }
    }

    // ------------------ inner --------------------

    private fun isChorusCoSinger(): Boolean {
        return singerRole == KTVSingRole.CoSinger
    }

    private fun sendStreamMessageWithJsonObject(
        obj: JSONObject,
        success: (isSendSuccess: Boolean) -> Unit
    ) {
        val ret = mRtcEngine.sendStreamMessage(innerDataStreamId, obj.toString().toByteArray())
        if (ret == 0) {
            success.invoke(true)
        } else {
            Log.e(TAG, "sendStreamMessageWithJsonObject failed: $ret")
        }
    }

    private fun sendStreamMessageWithJsonObjectEx(
        obj: JSONObject,
        success: (isSendSuccess: Boolean) -> Unit
    ) {
        val ret = mRtcEngine.sendStreamMessageEx(
            innerAudienceChannelStreamId,
            obj.toString().toByteArray(),
            subChorusConnection
        )
        if (ret == 0) {
            success.invoke(true)
        } else {
            Log.e(TAG, "sendStreamMessageWithJsonObjectEx failed: $ret")
        }
    }

    private fun syncPlayState(
        state: Constants.MediaPlayerState,
        error: Constants.MediaPlayerError
    ) {
        val msg: MutableMap<String?, Any?> = HashMap()
        msg["cmd"] = "PlayerState"
        msg["state"] = Constants.MediaPlayerState.getValue(state)
        msg["error"] = Constants.MediaPlayerError.getValue(error)
        val jsonMsg = JSONObject(msg)
        sendStreamMessageWithJsonObject(jsonMsg) {}
    }

    private fun syncPlayProgress(time: Long) {
        val msg: MutableMap<String?, Any?> = HashMap()
        msg["cmd"] = "Seek"
        msg["position"] = time
        val jsonMsg = JSONObject(msg)
        sendStreamMessageWithJsonObject(jsonMsg) {}
    }

    // 合唱
//    private fun joinChorus2ndChannel(
//        newRole: KTVSingRole,
//        token: String,
//        mainSingerUid: Int,
//        onJoinChorus2ndChannelCallback: (status: Int?) -> Unit
//    ) {
//        Log.d(TAG, "joinChorus2ndChannel: token:$token")
//        if (newRole == KTVSingRole.SoloSinger || newRole == KTVSingRole.Audience) {
//            Log.e(TAG, "joinChorus2ndChannel with wrong role: $newRole")
//            return
//        }
//
//        if (newRole == KTVSingRole.CoSinger) {
//            mRtcEngine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":false}")
//            mRtcEngine.setParameters("{\"che.audio.neteq.enable_stable_playout\":false}")
//            mRtcEngine.setParameters("{\"che.audio.custom_bitrate\": 48000}")
//            mRtcEngine.setAudioScenario(AUDIO_SCENARIO_CHORUS)
//        }
//
//        // main singer do not subscribe 2nd channel
//        // co singer auto sub
//        val channelMediaOption = ChannelMediaOptions()
//        channelMediaOption.autoSubscribeAudio =
//            newRole != KTVSingRole.LeadSinger
//        channelMediaOption.autoSubscribeVideo = false
//        channelMediaOption.publishMicrophoneTrack = newRole == KTVSingRole.LeadSinger
//        channelMediaOption.enableAudioRecordingOrPlayout =
//            newRole != KTVSingRole.LeadSinger
//        channelMediaOption.clientRoleType = CLIENT_ROLE_BROADCASTER
//
//        val rtcConnection = RtcConnection()
//        rtcConnection.channelId = ktvApiConfig.chorusChannelName
//        rtcConnection.localUid = ktvApiConfig.localUid
//        subChorusConnection = rtcConnection
//
//        val ret = mRtcEngine.joinChannelEx(
//            token,
//            rtcConnection,
//            channelMediaOption,
//            object : IRtcEngineEventHandler() {
//                override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
////                    Log.d(TAG, "onJoinChannel2Success: channel:$channel, uid:$uid")
////                    if (isRelease) return
//                    super.onJoinChannelSuccess(channel, uid, elapsed)
////                    if (newRole == KTVSingRole.LeadSinger) {
////                        mainSingerHasJoinChannelEx = true
////                    }
////                    onJoinChorus2ndChannelCallback(0)
////                    mRtcEngine.enableAudioVolumeIndicationEx(50, 10, true, rtcConnection)
//                }
//
//                override fun onLeaveChannel(stats: RtcStats?) {
//                    Log.d(TAG, "onLeaveChannel2")
//                    if (isRelease) return
//                    super.onLeaveChannel(stats)
//                }
//
//                override fun onError(err: Int) {
//                    super.onError(err)
//                    if (isRelease) return
//                    if (err == ERR_JOIN_CHANNEL_REJECTED) {
//                        Log.e(TAG, "joinChorus2ndChannel failed: ERR_JOIN_CHANNEL_REJECTED")
//                        onJoinChorus2ndChannelCallback(ERR_JOIN_CHANNEL_REJECTED)
//                    } else if (err == ERR_LEAVE_CHANNEL_REJECTED) {
//                        Log.e(TAG, "leaveChorus2ndChannel failed: ERR_LEAVE_CHANNEL_REJECTED")
//                    }
//                }
//
//                override fun onTokenPrivilegeWillExpire(token: String?) {
//                    super.onTokenPrivilegeWillExpire(token)
//                    ktvApiEventHandlerList.forEach { it.onTokenPrivilegeWillExpire() }
//                }
//
//                override fun onAudioVolumeIndication(
//                    speakers: Array<out AudioVolumeInfo>?,
//                    totalVolume: Int
//                ) {
//                    super.onAudioVolumeIndication(speakers, totalVolume)
//                    ktvApiEventHandlerList.forEach {
//                        it.onChorusChannelAudioVolumeIndication(
//                            speakers,
//                            totalVolume
//                        )
//                    }
//                }
//            }
//        )
//
//        if (ret != 0) {
//            Log.e(TAG, "joinChorus2ndChannel failed: $ret")
//        }
//
//        if (newRole == KTVSingRole.CoSinger) {
//            mRtcEngine.muteRemoteAudioStream(mainSingerUid, true)
//            Log.d(TAG, "muteRemoteAudioStream$mainSingerUid")
//        }
//    }

//    private fun leaveChorus2ndChannel(role: KTVSingRole) {
//        if (role == KTVSingRole.LeadSinger) {
//            mRtcEngine.leaveChannelEx(subChorusConnection)
//        } else if (role == KTVSingRole.CoSinger) {
//            mRtcEngine.leaveChannelEx(subChorusConnection)
//            mRtcEngine.muteRemoteAudioStream(mainSingerUid, false)
//        }
//    }

    // ------------------ 歌词播放、同步 ------------------
    // 开始播放歌词

    private val displayLrcTask = object : Runnable {
        override fun run() {
            if (!mStopDisplayLrc) {
                if (singerRole == KTVSingRole.Audience && !recvFromDataStream) return // 新增
                val lastReceivedTime = mLastReceivedPlayPosTime ?: return
                val curTime = System.currentTimeMillis()
                val offset = curTime - lastReceivedTime
                if (offset <= 100) {
                    val curTs = mReceivedPlayPosition + offset
                    if (singerRole == KTVSingRole.LeadSinger || singerRole == KTVSingRole.SoloSinger) { // 主唱发送歌词进度信息 AudioMetaData
                        val lrcTime = LrcTimeOuterClass.LrcTime.newBuilder()
                            .setTypeValue(LrcTimeOuterClass.MsgType.LRC_TIME.number)
                            .setForward(true)
                            .setSongId(songIdentifier)
                            .setTs(curTs)
                            .setUid(ktvApiConfig.chorusUid) // 歌词信息要携带进音乐流中, 这里需要传音乐Uid
                            .build()

                        val ret = mRtcEngine.sendAudioMetadataEx(
                            lrcTime.toByteArray(),
                            mpkConnection
                        ) // mpkConnection 是音乐这条流的RtcConnection
                    }
                    runOnMainThread {
//                        lrcView?.onUpdatePitch(pitch.toFloat())
                        // (fix ENT-489)Make lyrics delay for 200ms
                        // Per suggestion from Bob, it has a intrinsic buffer/delay between sound and `onPositionChanged(Player)`,
                        // such as AEC/Player/Device buffer.
                        // We choose the estimated 200ms.
                        var progress = if (curTs > 200) (curTs - 200) else curTs
                        if (singerRole == KTVSingRole.Audience && !forward) {
                            if (progress > 1500)
                                progress -= 1500
                        }
                        mLastSetPlayPosTime = curTs
//                        Log.i(TAG, "play_status_seek4: " + mReceivedPlayPosition)//59069
                        if (mediaPlayerState == MediaPlayerState.PLAYER_STATE_PLAYING) {
                            // The delay here will impact both singer and audience side
                            Log.d("xiru", "onUpdateProgress: $progress")
                            lrcView?.onUpdateProgress(progress, mReceivedPlayPosition)
                        } else {
                            lrcView?.onUpdateProgress(progress, 0)
                        }
                    }
                }
            }
        }
    }

    private var displayLrcFuture: ScheduledFuture<*>? = null
    private fun startDisplayLrc() {
        Log.d(TAG, "startDisplayLrc called")
        mStopDisplayLrc = false
        displayLrcFuture =
            scheduledThreadPool.scheduleAtFixedRate(displayLrcTask, 0, 20, TimeUnit.MILLISECONDS)
    }

    // 停止播放歌词
    private fun stopDisplayLrc() {
        Log.d(TAG, "stopDisplayLrc called")
        mStopDisplayLrc = true
        displayLrcFuture?.cancel(true)
        displayLrcFuture = null
        if (scheduledThreadPool is ScheduledThreadPoolExecutor) {
            scheduledThreadPool.remove(displayLrcTask)
        }
    }

    // ------------------ 音高pitch同步 ------------------
    private var mStopSyncPitch = true

    private val mSyncPitchTask = Runnable {
        if (!mStopSyncPitch) {
            if (mediaPlayerState == MediaPlayerState.PLAYER_STATE_PLAYING &&
                (singerRole == KTVSingRole.LeadSinger || singerRole == KTVSingRole.SoloSinger)
            ) {
                sendSyncPitch(pitch)
            }
        }
    }

    private fun sendSyncPitch(pitch: Double) {
        val msg: MutableMap<String?, Any?> = java.util.HashMap()
        msg["cmd"] = "setVoicePitch"
        msg["pitch"] = pitch
        msg["songIdentifier"] = songIdentifier
        msg["forward"] = true
        val jsonMsg = JSONObject(msg)
        sendStreamMessageWithJsonObject(jsonMsg) {}
    }

    // 开始同步音高
    private var mSyncPitchFuture: ScheduledFuture<*>? = null
    private fun startSyncPitch() {
        mStopSyncPitch = false
        mSyncPitchFuture =
            scheduledThreadPool.scheduleAtFixedRate(mSyncPitchTask, 0, 50, TimeUnit.MILLISECONDS)
    }

    // 停止同步音高
    private fun stopSyncPitch() {
        mStopSyncPitch = true
        pitch = 0.0

        mSyncPitchFuture?.cancel(true)
        mSyncPitchFuture = null
        if (scheduledThreadPool is ScheduledThreadPoolExecutor) {
            scheduledThreadPool.remove(mSyncPitchTask)
        }
    }

    // ------------------ 评分驱动混音同步 ------------------
    private var mStopSyncScore = true

    private val mSyncScoreTask = Runnable {
        if (!mStopSyncScore) {
            if (mediaPlayerState == MediaPlayerState.PLAYER_STATE_PLAYING &&
                (singerRole == KTVSingRole.LeadSinger || singerRole == KTVSingRole.CoSinger)
            ) {
                sendSyncScore()
            }
        }
    }

    private fun sendSyncScore() {
        val jsonObject = JSONObject()
        jsonObject.put("service", "audio_smart_mixer") // data message的目标消费者（服务）名
        jsonObject.put("version", "V1") //协议版本号（而非服务版本号）
        val payloadJson = JSONObject()
        payloadJson.put("cname", ktvApiConfig.channelName) // 频道名，主频道
        payloadJson.put("uid", ktvApiConfig.localUid.toString()) // 自己的uid，主频道
        payloadJson.put("uLv", -1) //user-leve1（用户级别，若无则为 -1，Level 越高，越重要）
        payloadJson.put("specialLabel", 0) //0: default-mode ，1：这个用户需要被排除出智能混音
        payloadJson.put("audioRoute", audioRoute) //音频路由：监听 onAudioRouteChanged
        payloadJson.put("vocalScore", singingScore) //单句打分
        jsonObject.put("payload", payloadJson)
//        Log.d(TAG, "sendSyncScore: $jsonObject")
//        AgoraLogUtils.getInstance().writeLog(
//            TAG, "sendSyncScore: $jsonObject"
//        )
        sendStreamMessageWithJsonObject(jsonObject) {}
    }

    // 开始发送分数
    private var mSyncScoreFuture: ScheduledFuture<*>? = null
    private fun startSyncScore() {
        mStopSyncScore = false
        mSyncScoreFuture =
            scheduledThreadPool.scheduleAtFixedRate(mSyncScoreTask, 0, 3000, TimeUnit.MILLISECONDS)
    }

    // 停止发送分数
    private fun stopSyncScore() {
        mStopSyncScore = true
        singingScore = 0

        mSyncScoreFuture?.cancel(true)
        mSyncScoreFuture = null
        if (scheduledThreadPool is ScheduledThreadPoolExecutor) {
            scheduledThreadPool.remove(mSyncScoreTask)
        }
    }

    // ------------------ 云端合流信息同步 ------------------
    private var mStopSyncCloudConvergenceStatus = true

    private val mSyncCloudConvergenceStatusTask = Runnable {
        if (!mStopSyncCloudConvergenceStatus) {
            if (singerRole == KTVSingRole.LeadSinger) {
                sendSyncCloudConvergenceStatus()
            }
        }
    }

    private fun sendSyncCloudConvergenceStatus() {
        val jsonObject = JSONObject()
        jsonObject.put("service", "audio_smart_mixer_status") // data message的目标消费者（服务）名
        jsonObject.put("version", "V1") //协议版本号（而非服务版本号）
        val payloadJson = JSONObject()
        payloadJson.put("Ts", getNtpTimeInMs()) // NTP 时间
        payloadJson.put("cname", ktvApiConfig.channelName) // 频道名
        payloadJson.put(
            "status",
            getCloudConvergenceStatus()
        ) //（-1： unknown，0：非K歌状态，1：K歌播放状态，2：K歌暂停状态）
        payloadJson.put("bgmUID", ktvApiConfig.chorusUid.toString()) // mpk流的uid
        payloadJson.put("leadsingerUID", mainSingerUid.toString()) //（"-1" = unknown） //主唱Uid
        jsonObject.put("payload", payloadJson)
//        Log.d(TAG, "sendSyncCloudConvergenceStatus: $jsonObject")
//        AgoraLogUtils.getInstance().writeLog(
//            TAG, "sendSyncCloudConvergenceStatus: $jsonObject"
//        )
        sendStreamMessageWithJsonObject(jsonObject) {}
    }

    // -1： unknown，0：非K歌状态，1：K歌播放状态，2：K歌暂停状态）
    private fun getCloudConvergenceStatus(): Int {
        var status = 0
        when (this.mediaPlayerState) {
            MediaPlayerState.PLAYER_STATE_PLAYING -> status = 1
            MediaPlayerState.PLAYER_STATE_PAUSED -> status = 2
            else -> {}
        }
        return status
    }

    // 开始发送分数
    private var mSyncCloudConvergenceStatusFuture: ScheduledFuture<*>? = null
    private fun startSyncCloudConvergenceStatus() {
        mStopSyncCloudConvergenceStatus = false
        mSyncCloudConvergenceStatusFuture = scheduledThreadPool.scheduleAtFixedRate(
            mSyncCloudConvergenceStatusTask,
            0,
            200,
            TimeUnit.MILLISECONDS
        )
    }

    // 停止发送分数
    private fun stopSyncCloudConvergenceStatus() {
        mStopSyncCloudConvergenceStatus = true

        mSyncCloudConvergenceStatusFuture?.cancel(true)
        mSyncCloudConvergenceStatusFuture = null
        if (scheduledThreadPool is ScheduledThreadPoolExecutor) {
            scheduledThreadPool.remove(mSyncCloudConvergenceStatusTask)
        }
    }

    private fun loadLyric(
        songNo: Long,
        onLoadLyricCallback: (songNo: Long, lyricUrl: String?) -> Unit
    ) {
        Log.d(TAG, "loadLyric: $songNo")
        val requestId = mMusicCenter.getLyric(songNo, LyricType.KRC)
        if (requestId.isEmpty()) {
            onLoadLyricCallback.invoke(songNo, null)
            return
        }
        lyricSongCodeMap[requestId] = songNo
        lyricCallbackMap[requestId] = onLoadLyricCallback
    }

    private fun preLoadMusic(
        songNo: Long, onLoadMusicCallback: (
            songCode: Long,
            percent: Int,
            status: MccExState,
            msg: String?,
            lyricUrl: String?
        ) -> Unit
    ) {
        Log.d(TAG, "loadMusic: $songNo")
        val ret = mMusicCenter.isPreloaded(songNo)
        if (ret == 0) {
            loadMusicCallbackMap.remove(songNo.toString())
            onLoadMusicCallback(songNo, 100, MccExState.PRELOAD_STATE_COMPLETED, null, null)
            return
        }

//        val jsonOption = "{\"format\":{\"qualityLevel\":1}}"
//        val songCodeTmp = mMusicCenter.getInternalSongCode(songNo, jsonOption)
        val retPreload = mMusicCenter.preload(songNo)
        if (retPreload == "") {
            Log.e(TAG, "preLoadMusic failed: $retPreload")
            loadMusicCallbackMap.remove(songNo.toString())
            onLoadMusicCallback(songNo, 100, MccExState.PRELOAD_STATE_FAILED, null, null)
            return
        }
        loadMusicCallbackMap[songNo.toString()] = onLoadMusicCallback
    }

    private fun getNtpTimeInMs(): Long {
        val currentNtpTime = mRtcEngine.ntpWallTimeInMs
        return if (currentNtpTime != 0L) {
            currentNtpTime + 2208988800L * 1000
        } else {
            Log.e(TAG, "getNtpTimeInMs DeviceDelay is zero!!!")
            System.currentTimeMillis()
        }
    }

    private fun runOnMainThread(r: Runnable) {
        if (Thread.currentThread() == mainHandler.looper.thread) {
            r.run()
        } else {
            mainHandler.post(r)
        }
    }

    // ------------------------ AgoraRtcEvent ------------------------
    override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
        super.onStreamMessage(uid, streamId, data)
        val jsonMsg: JSONObject
        val messageData = data ?: return
        try {
            val strMsg = String(messageData)
            jsonMsg = JSONObject(strMsg)
            if (!jsonMsg.has("cmd")) return
            if (jsonMsg.getString("cmd") == "setLrcTime") { //同步歌词
                val position = jsonMsg.getLong("time")
                val realPosition = jsonMsg.getLong("realTime")
                val duration = jsonMsg.getLong("duration")
                val remoteNtp = jsonMsg.getLong("ntp")
                val songId = jsonMsg.getString("songIdentifier")
                val mpkState = jsonMsg.getInt("playerState")

                if (isChorusCoSinger()) {
                    // 本地BGM校准逻辑
                    if (this.mediaPlayerState == MediaPlayerState.PLAYER_STATE_OPEN_COMPLETED) {
                        // 合唱者开始播放音乐前调小远端人声
//                        mRtcEngine.adjustPlaybackSignalVolume(remoteVolume)
                        // 收到leadSinger第一次播放位置消息时开启本地播放（先通过seek校准）
                        val delta = getNtpTimeInMs() - remoteNtp
                        val expectPosition = position + delta + audioPlayoutDelay
                        if (expectPosition in 1 until duration) {
                            mPlayer.seek(expectPosition)
                        }
                        mPlayer.play()
                    } else if (this.mediaPlayerState == MediaPlayerState.PLAYER_STATE_PLAYING) {
                        val localNtpTime = getNtpTimeInMs()
                        val localPosition =
                            localNtpTime - this.localPlayerSystemTime + this.localPlayerPosition // 当前副唱的播放时间
                        val expectPosition =
                            localNtpTime - remoteNtp + position + audioPlayoutDelay // 实际主唱的播放时间
                        val diff = expectPosition - localPosition
//                        Log.i(
//                            TAG,
//                            "play_status_seek: " + diff + "  localNtpTime: " + localNtpTime + "  expectPosition: " + expectPosition +
//                                    "  localPosition: " + localPosition + "  ntp diff: " + (localNtpTime - remoteNtp) + "__duration:" + duration
//                        )
                        if ((diff > 80 || diff < -80) && expectPosition < duration) { //设置阈值为80ms，避免频繁seek
                            mPlayer.seek(expectPosition)
                        }
                    } else {
                        mLastReceivedPlayPosTime = System.currentTimeMillis()
                        mReceivedPlayPosition = realPosition
                    }

                    if (MediaPlayerState.getStateByValue(mpkState) != this.mediaPlayerState) {
                        when (MediaPlayerState.getStateByValue(mpkState)) {
                            MediaPlayerState.PLAYER_STATE_PAUSED -> {
                                mPlayer.pause()
                            }

                            MediaPlayerState.PLAYER_STATE_PLAYING -> {
                                mPlayer.resume()
                            }

                            else -> {}
                        }
                    }
                } else {
                    // 观众
                    if (jsonMsg.has("ver")) {
                        // 发送端是新发送端, 歌词信息需要从 audioMetadata 里取
                        recvFromDataStream = false
                    } else {
                        // 发送端是老发送端, 歌词信息需要从 dataStreamMessage 里取
                        recvFromDataStream = true
                        if (this.songIdentifier == songId) {
                            if (!forward) {
                                mLastReceivedPlayPosTime = System.currentTimeMillis()
                                mReceivedPlayPosition = realPosition
                            }
                        } else {
                            mLastReceivedPlayPosTime = 0
                            mReceivedPlayPosition = 0
                        }
                    }
                }

//                AgoraLogUtils.getInstance().writeLog(
//                    TAG, "onPositionChanged: $strMsg"
//                )
            } else if (jsonMsg.getString("cmd") == "Seek") {
                // 伴唱收到原唱seek指令
                if (isChorusCoSinger()) {
                    val position = jsonMsg.getLong("position")
                    mPlayer.seek(position)
                }
            } else if (jsonMsg.getString("cmd") == "PlayerState") {
                // 其他端收到原唱seek指令
                val state = jsonMsg.getInt("state")
                val error = jsonMsg.getInt("error")
                Log.d(TAG, "onStreamMessage PlayerState: $state")
                if (isChorusCoSinger()) {
                    when (MediaPlayerState.getStateByValue(state)) {
                        MediaPlayerState.PLAYER_STATE_PAUSED -> {
                            mPlayer.pause()
                        }

                        MediaPlayerState.PLAYER_STATE_PLAYING -> {
                            mPlayer.resume()
                        }

                        else -> {}
                    }
                } else if (this.singerRole == KTVSingRole.Audience) {
                    this.mediaPlayerState = MediaPlayerState.getStateByValue(state)
                }
                ktvApiEventHandlerList.forEach {
                    it.onMusicPlayerStateChanged(
                        MediaPlayerState.getStateByValue(state),
                        Constants.MediaPlayerError.getErrorByValue(error),
                        false
                    )
                }
            } else if (jsonMsg.getString("cmd") == "setVoicePitch") {
                if (ktvScene == 0) return

                val pitch = jsonMsg.getDouble("pitch")
                //观众和合唱者都显示主唱的音高线
                if ((this.singerRole == KTVSingRole.Audience && !forward) || this.singerRole == KTVSingRole.CoSinger) {
                    this.pitch = pitch
                }
            }
        } catch (exp: JSONException) {
            Log.e(TAG, "onStreamMessage:$exp")
        }
    }

    override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
        super.onAudioVolumeIndication(speakers, totalVolume)
        val allSpeakers = speakers ?: return
        // VideoPitch 回调, 用于同步各端音准
        if (this.singerRole == KTVSingRole.LeadSinger || this.singerRole == KTVSingRole.SoloSinger
            || (ktvScene == 0 && this.singerRole == KTVSingRole.CoSinger)
        ) {
            for (info in allSpeakers) {
                if (info.uid == 0) {
                    pitch =
                        if (this.mediaPlayerState == MediaPlayerState.PLAYER_STATE_PLAYING
                            // && (RTCAgoraApi.hasOpenLicaoAudio || RTCChannelApi.hasChannelOpenLicaoAudio) // TODO modify
                        ) {
                            info.voicePitch
                        } else {
                            0.0
                        }
                }
            }
        }
    }

    // 用于合唱校准
    override fun onLocalAudioStats(stats: LocalAudioStats?) {
        super.onLocalAudioStats(stats)
        if (useCustomAudioSource) return
        val audioState = stats ?: return
        audioPlayoutDelay = audioState.audioPlayoutDelay
    }

    // 音频路由监听
    override fun onAudioRouteChanged(routing: Int) {
        super.onAudioRouteChanged(routing)
        this.audioRoute = routing
    }

    override fun onUserJoined(uid: Int, elapsed: Int) {
        super.onUserJoined(uid, elapsed)
        if (singerRole != KTVSingRole.Audience && uid == ktvApiConfig.chorusUid) {
            mRtcEngine.muteRemoteAudioStream(ktvApiConfig.chorusUid, true)
        }
    }

    override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
        super.onJoinChannelSuccess(channel, uid, elapsed)
        if (singerRole == KTVSingRole.LeadSinger) {

        }
    }

    override fun onLeaveChannel(stats: RtcStats?) {
        super.onLeaveChannel(stats)
        if (singerRole == KTVSingRole.LeadSinger) {
            stopSyncCloudConvergenceStatus()
        }
    }

    // ------------------------ AgoraRtcMediaPlayerDelegate ------------------------
    private var duration: Long = 0
    override fun onPlayerStateChanged(
        state: Constants.MediaPlayerState?,
        error: Constants.MediaPlayerError?
    ) {
        val mediaPlayerState = state ?: return
        val mediaPlayerError = error ?: return
        Log.d(TAG, "onPlayerStateChanged called, state: $mediaPlayerState, error: $error")
        this.mediaPlayerState = mediaPlayerState
        when (mediaPlayerState) {
            MediaPlayerState.PLAYER_STATE_OPEN_COMPLETED -> {
                duration = mPlayer.duration
                this.localPlayerPosition = 0
//                mPlayer.selectAudioTrack(1)
                if (this.singerRole == KTVSingRole.SoloSinger ||
                    this.singerRole == KTVSingRole.LeadSinger
                ) {
                    mPlayer.play()
                }
            }

            MediaPlayerState.PLAYER_STATE_PLAYING -> {
//                mRtcEngine.adjustPlaybackSignalVolume(remoteVolume)
            }

            MediaPlayerState.PLAYER_STATE_PAUSED -> {
                mRtcEngine.adjustPlaybackSignalVolume(100)
            }

            MediaPlayerState.PLAYER_STATE_STOPPED -> {
                mRtcEngine.adjustPlaybackSignalVolume(100)
                duration = 0
            }

            else -> {}
        }

        if (this.singerRole == KTVSingRole.SoloSinger || this.singerRole == KTVSingRole.LeadSinger) {
            syncPlayState(mediaPlayerState, mediaPlayerError)
        }
        ktvApiEventHandlerList.forEach {
            it.onMusicPlayerStateChanged(
                mediaPlayerState,
                mediaPlayerError,
                true
            )
        }
    }

    // 同步播放进度
    override fun onPositionChanged(position_ms: Long, timestamp_ms: Long) {
        Log.d("xiru", "onPositionChanged: $position_ms")
        localPlayerPosition = position_ms
        localPlayerSystemTime = timestamp_ms

        if ((this.singerRole == KTVSingRole.SoloSinger || this.singerRole == KTVSingRole.LeadSinger) && position_ms > audioPlayoutDelay) {
            val msg: MutableMap<String?, Any?> = HashMap()
            msg["cmd"] = "setLrcTime"
            msg["ntp"] = timestamp_ms
            if (forward)
                msg["forward"] = true
            msg["ver"] = 2 // 新增的字段
            msg["duration"] = duration
            msg["time"] =
                position_ms - audioPlayoutDelay // "position-audioDeviceDelay" 是计算出当前播放的真实进度
            msg["realTime"] = position_ms
            msg["playerState"] = MediaPlayerState.getValue(this.mediaPlayerState)
            msg["pitch"] = pitch
            msg["songIdentifier"] = songIdentifier
            val jsonMsg = JSONObject(msg)
//            Log.d(TAG, "setLrcTime_send:$jsonMsg")
            sendStreamMessageWithJsonObject(jsonMsg) {}
        }

        if (this.singerRole != KTVSingRole.Audience) {
            mLastReceivedPlayPosTime = System.currentTimeMillis()
            mReceivedPlayPosition = position_ms

//            Log.i(TAG, "play_status_seek2: " + mReceivedPlayPosition)
            ktvApiEventHandlerList.forEach {
                it.onPositionChanged(position_ms - audioPlayoutDelay, timestamp_ms)
            }
        } else {
            mLastReceivedPlayPosTime = 0
            mReceivedPlayPosition = 0
        }
    }

    override fun onPlayerEvent(
        eventCode: Constants.MediaPlayerEvent?,
        elapsedTime: Long,
        message: String?
    ) {
        //合唱用户主动seek
        if ((eventCode == Constants.MediaPlayerEvent.PLAYER_EVENT_SEEK_COMPLETE
                    || eventCode == Constants.MediaPlayerEvent.PLAYER_EVENT_SEEK_ERROR)
        ) {
            //seek 完成
        }
    }

    override fun onMetaData(type: Constants.MediaPlayerMetadataType?, data: ByteArray?) {}

    override fun onPlayBufferUpdated(playCachedBuffer: Long) {}

    override fun onPreloadEvent(src: String?, event: Constants.MediaPlayerPreloadEvent?) {}

    override fun onAgoraCDNTokenWillExpire() {}

    override fun onPlayerSrcInfoChanged(from: SrcInfo?, to: SrcInfo?) {}

    override fun onPlayerInfoUpdated(info: PlayerUpdatedInfo?) {}

    override fun onAudioVolumeIndication(volume: Int) {}

    //业务自定义的功能方法
    /**
     * 获取缓存中所有的歌曲
     */
    // TODO hide
//    override fun getAllCache(): List<MusicCacheInfo>? {
//        if (this::mMusicCenter.isInitialized) {
//            return mMusicCenter.caches.toList()
//        }
//        return null
//    }
//
//    /**
//     * 移除缓存中某个歌曲
//     */
//    override fun removeCache(songCode: Long) {
//        if (this::mMusicCenter.isInitialized) {
//            mMusicCenter.removeCache(songCode)
//        }
//    }

    /**
     * 调整本地播放音量
     */
    fun adjustPlayoutVolume(mpkPlayoutVolume: Int): Int {
        if (this::mPlayer.isInitialized) {
            if (singerRole == KTVSingRole.LeadSinger) {//合唱主持
                mPlayer.adjustPublishSignalVolume(mpkPlayoutVolume)
                return mPlayer.adjustPlayoutVolume(mpkPlayoutVolume)
            }
            return mPlayer.adjustPlayoutVolume(mpkPlayoutVolume)
        }

        return -1
    }


    /**
     * 设置播放声道
     */
    fun setAudioDualMonoMode(mode: Int): Int {
        if (this::mPlayer.isInitialized)
            return mPlayer.setAudioDualMonoMode(mode)
        return -1
    }


    /**
     * 歌曲是否加载
     */
    fun isPreloaded(songCode: Long): Int {
        if (this::mPlayer.isInitialized)
            return mMusicCenter.isPreloaded(songCode)
        return -1
    }

    /**
     * 获取播放进度
     */
    fun getPlayPosition(): Long {
        if (this::mPlayer.isInitialized)
            return mPlayer.playPosition
        return -1
    }

    /**
     * 获取时长
     */
    fun getSongDuration(): Long {
        if (this::mPlayer.isInitialized)
            return mPlayer.duration
        return -1
    }


    /**
     * 设置音轨
     */
    fun selectAudioTrack(index: Int): Int {
        if (this::mPlayer.isInitialized)
            return mPlayer.selectAudioTrack(index)
        return -1
    }

    /**
     * 更新子频道的channel信息
     */
    fun updateChorusChannelInfo(
        chorusChannelName: String, chorusChannelToken: String,
        chorusUid: Int, hostBgmChannelKey: String, forward: Boolean, ktvScene: Int
    ) {
        if (this::ktvApiConfig.isInitialized) {
            ktvApiConfig.chorusUid = chorusUid
            ktvApiConfig.mpkChannelToken = hostBgmChannelKey
            ktvApiConfig.chorusChannelName = chorusChannelName
            ktvApiConfig.chorusChannelToken = chorusChannelToken
            this.forward = forward
            this.ktvScene = ktvScene
        }
    }

    /**
     * 更新观众的歌词进度
     */
    fun updateLrcViewTime(
        realPosition: Long, isReset: Boolean = false, isMetaData: Boolean = false
    ) {
        if (this::ktvApiConfig.isInitialized) {
            if (isReset) {
                mLastReceivedPlayPosTime = 0
                mReceivedPlayPosition = 0
                return
            }
            if (isMetaData) {//使用metaData传出的数据
                runOnMainThread {
                    lrcView?.onUpdateProgress(
                        if (realPosition > 300) (realPosition - 300) else realPosition,
                        0
                    )
                }
                return
            }

            // 独唱观众
            val lastTime = mLastReceivedPlayPosTime
            val recvTimeDiff: Long = System.currentTimeMillis() - lastTime
            val mpkPosDiff: Long = realPosition - mReceivedPlayPosition
            // 1、currentTime = 0 继续执行
            // 2、recvTimeDiff - mpkPosDiff > 3000 说明该包为超时包， 丢弃return
            // 3、mpkPosDiff < 0 回退包， 丢弃return
            // 4、(lastSetPosition - realPosition) > 1000 允许校准时间1000ms
            // 1、currentTime = 0 继续执行
            // 2、recvTimeDiff - mpkPosDiff > 3000 说明该包为超时包， 丢弃return
            // 3、mpkPosDiff < 0 回退包， 丢弃return// 4、(lastSetPosition - realPosition) > 1000 允许校准时间1000ms
            if (ktvScene == 0 && lastTime != 0L && (recvTimeDiff - mpkPosDiff > 3000 || mpkPosDiff < 0 || (mLastSetPlayPosTime - realPosition) > 1000)) {
                return
            }
            mLastReceivedPlayPosTime = System.currentTimeMillis()
            mReceivedPlayPosition = realPosition
        }
    }

    /**
     * 更新观众的歌词进度
     */
    fun updateLrcViewPitch(realPitch: Double, isReset: Boolean = false) {
        if (this::ktvApiConfig.isInitialized) {
            if (isReset) {
                pitch = 0.0
                return
            }
            pitch = realPitch
        }
    }

    // IMusicContentCenterExEventHandler
    override fun onInitializeResult(state: MccExState, reason: MccExStateReason) {

    }

    override fun onLyricResult(
        requestId: String,
        songCode: Long,
        lyricPath: String,
        offsetBegin: Int,
        offsetEnd: Int,
        reason: MccExStateReason
    ) {
        Log.d("xiru", "onLyricResult, requestId:$requestId, songCode:$songCode, lyricPath:$lyricPath, reason:$reason")
        val callback = lyricCallbackMap[requestId] ?: return
        val songCode = lyricSongCodeMap[requestId] ?: return
        lyricCallbackMap.remove(lyricPath)
        if (reason == MccExStateReason.STATE_REASON_YSD_ERROR_TOKEN_ERROR) {
            // Token过期
            ktvApiEventHandlerList.forEach { it.onTokenPrivilegeWillExpire() }
        }
        if (lyricPath == null || lyricPath.isEmpty()) {
            callback(songCode, null)
            return
        }
        callback(songCode, lyricPath)
    }

    override fun onPitchResult(
        requestId: String,
        songCode: Long,
        pitchPath: String,
        offsetBegin: Int,
        offsetEnd: Int,
        reason: MccExStateReason
    ) {

    }

    override fun onPreLoadEvent(
        requestId: String,
        songCode: Long,
        percent: Int,
        lyricPath: String,
        pitchPath: String,
        offsetBegin: Int,
        offsetEnd: Int,
        state: MccExState,
        reason: MccExStateReason
    ) {
        Log.d("xiru", "onPreLoadEvent, requestId:$requestId, songCode:$songCode, percent:$percent, lyricPath:$lyricPath, pitchPath:$pitchPath, state:$state, reason:$reason")
        val callback = loadMusicCallbackMap[songCode.toString()] ?: return
        if (state == MccExState.PRELOAD_STATE_COMPLETED || state == MccExState.PRELOAD_STATE_FAILED || state == MccExState.PRELOAD_STATE_REMOVED) {
            loadMusicCallbackMap.remove(songCode.toString())
        }
        if (reason == MccExStateReason.STATE_REASON_YSD_ERROR_TOKEN_ERROR) {
            // Token过期
            ktvApiEventHandlerList.forEach { it.onTokenPrivilegeWillExpire() }
        }
        callback.invoke(songCode, percent, state, reason.reason.toString(), lyricPath)
    }

    override fun onStartScoreResult(songCode: Long, state: MccExState, reason: MccExStateReason) {
        val callback = startScoreMap.remove(songCode.toString())
        callback?.invoke(songCode, state, reason)
    }

    // IMusicContentCenterExScoreEventHandler
    override fun onLineScore(songCode: Long, value: LineScoreData) {
        Log.d("xiru", "onLineScore, songCode: $songCode, score: ${value.linePitchScore}")
        if (this.songCode == songCode) {
            this.singingScore = value.linePitchScore
        }
        ktvApiEventHandlerList.forEach {
            it.onLineScore(songCode, value)
        }
    }

    override fun onPitch(songCode: Long, data: RawScoreData) {

    }
}