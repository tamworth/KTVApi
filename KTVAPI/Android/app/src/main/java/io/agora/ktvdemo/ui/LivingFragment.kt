package io.agora.ktvdemo.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import io.agora.karaoke_view_ex.KaraokeView
import io.agora.ktvapi.*
import io.agora.ktvdemo.BuildConfig
import io.agora.ktvdemo.MyApplication
import io.agora.ktvdemo.R
import io.agora.ktvdemo.api.CloudApiManager
import io.agora.ktvdemo.databinding.FragmentLivingBinding
import io.agora.ktvdemo.rtc.RtcEngineController
import io.agora.ktvdemo.utils.KeyCenter
import io.agora.mccex.IMusicContentCenterEx
import io.agora.mccex.MusicContentCenterExConfiguration
import io.agora.mccex.constants.ChargeMode
import io.agora.mccex.constants.MccExState
import io.agora.mccex.model.YsdVendorConfigure
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcConnection
import java.io.File
import java.util.concurrent.Executors

class LivingFragment : BaseFragment<FragmentLivingBinding>() {

    private var karaokeView: KaraokeView? = null

    private val ktvApi: KTVApi by lazy {
        GiantChorusKTVApiImpl()
    }

    private val ktvApiEventHandler = object : IKTVApiEventHandler() {}

    private val scheduledThreadPool = Executors.newScheduledThreadPool(5)

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLivingBinding {
        return FragmentLivingBinding.inflate(inflater)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initView()
        initKTVApi()
        joinChannel()

        if (KeyCenter.isLeadSinger() || KeyCenter.isCoSinger()) {
            ktvApi.setMicStatus(false)
        }

        if (KeyCenter.isLeadSinger()) {
            scheduledThreadPool.execute {
                CloudApiManager.getInstance().fetchStartCloud(KeyCenter.channelId)
            }
        }
    }

    override fun onDestroy() {
        if (KeyCenter.isLeadSinger()) {
            scheduledThreadPool.execute {
                CloudApiManager.getInstance().fetchStopCloud()
            }
        }
        ktvApi.switchSingerRole(KTVSingRole.Audience, null)
        ktvApi.removeEventHandler(ktvApiEventHandler)
        ktvApi.release()
        RtcEngineController.rtcEngine.leaveChannel()
        super.onDestroy()
    }

    private fun initView() {
        binding?.apply {
            karaokeView = KaraokeView(lyricsView,null)

            // 退出场景
            btnClose.setOnClickListener {
                ktvApi.switchSingerRole(KTVSingRole.Audience, null)
                ktvApi.removeEventHandler(ktvApiEventHandler)
                ktvApi.release()
                RtcEngineController.rtcEngine.leaveChannel()
                findNavController().popBackStack()
            }
            if (KeyCenter.isLeadSinger()) {
                tvSinger.text = getString(R.string.app_lead_singer)
            } else if (KeyCenter.isCoSinger()) {
                tvSinger.text = getString(R.string.app_co_singer)
            } else {
                tvSinger.text = getString(R.string.app_audience)
            }

            // 加入合唱
            btJoinChorus.setOnClickListener {
                if (KeyCenter.isLeadSinger()) {
                    Toast.makeText(MyApplication.app(), R.string.app_no_premission, Toast.LENGTH_SHORT).show()
                } else {
                    ktvApi.switchSingerRole(KTVSingRole.CoSinger, null)
                }
            }

            // 退出合唱
            btLeaveChorus.setOnClickListener {
                if (KeyCenter.isLeadSinger()) {
                    Toast.makeText(MyApplication.app(), R.string.app_no_premission, Toast.LENGTH_SHORT).show()
                } else {
                    ktvApi.switchSingerRole(KTVSingRole.Audience, null)
                }
            }

            // 开原唱：仅领唱和合唱者可以做这项操作
            btOriginal.setOnClickListener {
                if (KeyCenter.isLeadSinger()) {
                    ktvApi.getMediaPlayer().selectMultiAudioTrack(0, 0)
                } else if (KeyCenter.isCoSinger()) {
                    ktvApi.getMediaPlayer().selectAudioTrack(0)
                } else {
                    Toast.makeText(MyApplication.app(), R.string.app_no_premission, Toast.LENGTH_SHORT).show()
                }
            }

            // 开伴奏：仅领唱和合唱者可以做这项操作
            btAcc.setOnClickListener {
                if (KeyCenter.isLeadSinger()) {
                    ktvApi.getMediaPlayer().selectMultiAudioTrack(1, 1)
                } else if (KeyCenter.isCoSinger()) {
                    ktvApi.getMediaPlayer().selectAudioTrack(1)
                } else {
                    Toast.makeText(MyApplication.app(), R.string.app_no_premission, Toast.LENGTH_SHORT).show()
                }
            }

            // 开导唱：仅领唱可以做这项操作，开启后领唱本地听到歌曲原唱，但观众听到仍为伴奏
            btDaoChang.setOnClickListener {
                if (KeyCenter.isLeadSinger()) {
                    ktvApi.getMediaPlayer().selectMultiAudioTrack(0, 1)
                } else if (KeyCenter.isCoSinger()) {
                    Toast.makeText(MyApplication.app(), R.string.app_no_premission, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(MyApplication.app(), R.string.app_no_premission, Toast.LENGTH_SHORT).show()
                }
            }

            // 加载音乐
            btLoadMusic.setOnClickListener {
                if (KeyCenter.isMcc) {
                    // 使用声网版权中心歌单
                    val musicConfiguration = KTVLoadMusicConfiguration(
                        KeyCenter.songCode.toString(), false, KeyCenter.LeadSingerUid,
                        if (KeyCenter.isAudience()) KTVLoadMusicMode.LOAD_LRC_ONLY else KTVLoadMusicMode.LOAD_MUSIC_AND_LRC
                    )
                    ktvApi.loadMusic(KeyCenter.songCode, musicConfiguration, object : IMusicLoadStateListener {
                        override fun onMusicLoadSuccess(songCode: Long, lyricUrl: String) {
                            Log.d("Music", "onMusicLoadSuccess, songCode: $songCode, lyricUrl: $lyricUrl")
                            ktvApi.startScore(KeyCenter.songCode) { _, state, _ ->
                                if (state == MccExState.START_SCORE_STATE_COMPLETED) {
                                    if (KeyCenter.isLeadSinger()) {
                                        ktvApi.switchSingerRole(KTVSingRole.LeadSinger, object : ISwitchRoleStateListener {
                                            override fun onSwitchRoleSuccess() {
                                                // 加载成功开始播放音乐
                                                ktvApi.startSing(songCode, 0)
                                            }

                                            override fun onSwitchRoleFail(reason: SwitchRoleFailReason) {

                                            }

                                            override fun onJoinChorusChannelSuccess(
                                                channel: String,
                                                uid: Int
                                            ) {

                                            }
                                        })
                                    } else if (KeyCenter.isCoSinger()) {
                                        ktvApi.switchSingerRole(KTVSingRole.CoSinger, object : ISwitchRoleStateListener {
                                            override fun onSwitchRoleSuccess() {

                                            }

                                            override fun onSwitchRoleFail(reason: SwitchRoleFailReason) {

                                            }

                                            override fun onJoinChorusChannelSuccess(
                                                channel: String,
                                                uid: Int
                                            ) {

                                            }
                                        })
                                    }
                                }
                            }
                        }

                        override fun onMusicLoadFail(songCode: Long, reason: KTVLoadSongFailReason) {
                            Log.d("Music", "onMusicLoadFail, songCode: $songCode, reason: $reason")
                        }

                        override fun onMusicLoadProgress(
                            songCode: Long,
                            percent: Int,
                            status: MusicLoadStatus,
                            msg: String?,
                            lyricUrl: String?
                        ) {
                            Log.d("Music", "onMusicLoadProgress, songCode: $songCode, percent: $percent")
                            lyricsView.post {
                                tvMusicProcess.text = "下载进度：$percent%"
                            }
                        }
                    })
                } else {

                    // 使用声网版权中心歌单
                    val musicConfiguration = KTVLoadMusicConfiguration(
                        KeyCenter.songCode2.toString(), false, KeyCenter.LeadSingerUid,
                        if (KeyCenter.isAudience()) KTVLoadMusicMode.LOAD_LRC_ONLY else KTVLoadMusicMode.LOAD_MUSIC_AND_LRC
                    )
                    ktvApi.loadMusic(KeyCenter.songCode2, musicConfiguration, object : IMusicLoadStateListener {
                        override fun onMusicLoadSuccess(songCode: Long, lyricUrl: String) {
                            Log.d("Music", "onMusicLoadSuccess, songCode: $songCode, lyricUrl: $lyricUrl")
                            if (KeyCenter.isLeadSinger()) {
                                ktvApi.switchSingerRole(KTVSingRole.LeadSinger, object : ISwitchRoleStateListener {
                                    override fun onSwitchRoleSuccess() {

                                        // 加载成功开始播放音乐
                                        ktvApi.startSing(songCode, 0)
                                    }

                                    override fun onSwitchRoleFail(reason: SwitchRoleFailReason) {

                                    }

                                    override fun onJoinChorusChannelSuccess(
                                        channel: String,
                                        uid: Int
                                    ) {

                                    }
                                })
                            } else if (KeyCenter.isCoSinger()) {
                                ktvApi.switchSingerRole(KTVSingRole.CoSinger, object : ISwitchRoleStateListener {
                                    override fun onSwitchRoleSuccess() {

                                    }

                                    override fun onSwitchRoleFail(reason: SwitchRoleFailReason) {

                                    }

                                    override fun onJoinChorusChannelSuccess(
                                        channel: String,
                                        uid: Int
                                    ) {

                                    }
                                })
                            }
                        }

                        override fun onMusicLoadFail(songCode: Long, reason: KTVLoadSongFailReason) {
                            Log.d("Music", "onMusicLoadFail, songCode: $songCode, reason: $reason")
                        }

                        override fun onMusicLoadProgress(
                            songCode: Long,
                            percent: Int,
                            status: MusicLoadStatus,
                            msg: String?,
                            lyricUrl: String?
                        ) {
                            Log.d("Music", "onMusicLoadProgress, songCode: $songCode, percent: $percent")
                            lyricsView.post {
                                tvMusicProcess.text = "下载进度：$percent%"
                            }
                        }
                    })
                }
            }

            // 取消加载歌曲并删除本地歌曲缓存
//            btRemoveMusic.setOnClickListener {
//                if (KeyCenter.isMcc) {
//                    ktvApi.removeMusic(KeyCenter.songCode)
//                    lyricsView.reset()
//                } else {
//                    ktvApi.removeMusic(KeyCenter.songCode2)
//                    lyricsView.reset()
//                }
//            }

            // 开麦
            btMicOn.setOnClickListener {
//                ktvApi.muteMic(false)
                Toast.makeText(MyApplication.app(), R.string.app_no_premission, Toast.LENGTH_SHORT).show()
            }

            // 关麦
            btMicOff.setOnClickListener {
                //ktvApi.muteMic(true)
                Toast.makeText(MyApplication.app(), R.string.app_no_premission, Toast.LENGTH_SHORT).show()
            }

            btPause.setOnClickListener {
                ktvApi.pauseSing()
            }

            btPlay.setOnClickListener {
                ktvApi.resumeSing()
            }
        }
    }

    private fun joinChannel() {
        // 观众通过joinChannelEx 加入观众频道
        if (KeyCenter.isAudience()) {
            val channelMediaOptions = ChannelMediaOptions().apply {
                autoSubscribeAudio = true
                clientRoleType = io.agora.rtc2.Constants.CLIENT_ROLE_AUDIENCE
                autoSubscribeVideo = true
                autoSubscribeAudio = true
                publishCameraTrack = false
                publishMicrophoneTrack = false
            }
            RtcEngineController.rtcEngine.joinChannelEx(
                RtcEngineController.audienceChannelToken,
                RtcConnection(KeyCenter.channelId + "_ad", KeyCenter.localUid),
                channelMediaOptions,
                object : IRtcEngineEventHandler() {
                    override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
                        //ktvApi.setAudienceStreamMessage(uid, streamId, data)
                    }

                    override fun onAudioMetadataReceived(uid: Int, data: ByteArray?) {
                        super.onAudioMetadataReceived(uid, data)
                        ktvApi.setAudioMetadata(data)
                    }
                }
            )
        } else {
            // 主唱和合唱通过 joinChannel 加入演唱频道
            val channelMediaOptions = ChannelMediaOptions().apply {
                autoSubscribeAudio = true
                clientRoleType = io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER
                autoSubscribeVideo = true
                autoSubscribeAudio = true
                publishCameraTrack = false
                publishMicrophoneTrack = true
            }
            RtcEngineController.rtcEngine.joinChannel(
                RtcEngineController.chorusChannelToken,
                KeyCenter.channelId, KeyCenter.localUid,
                channelMediaOptions
            )
        }
    }

    private fun initKTVApi() {
        // ------------------ 初始化内容中心 ------------------
        val contentCenterConfiguration = MusicContentCenterExConfiguration()
        contentCenterConfiguration.context = context
        contentCenterConfiguration.vendorConfigure = YsdVendorConfigure(
            appId = "203321",
            appKey = "4059144a3ace4a23a351ca3f96e6693d",
            token = "KNH5KJVBH2UML9GKDLV20HKGP47MP2MFQT02O5MV88IB38KU1VKQFF5CIH077NTSILP415C6F2EK7BC615ILQII9L4BSLQPL4G2DRRK0M5QT47LUJ4ETOS3U9OFMHOVKK6OQASPHT68J3PH95HSL4J6JUUF5BN4SKUJOG1LTNLGHSMR49E61NTTF39C09U1Q",
            userId = "ED5BBD86F7A853AF66A13DC5AA6A8863",
            deviceId = "2323",
            chargeMode = ChargeMode.ONCE,
            urlTokenExpireTime = 60 * 15
        )
        contentCenterConfiguration.enableLog = true
        contentCenterConfiguration.enableSaveLogToFile = true
        contentCenterConfiguration.logFilePath = context?.getExternalFilesDir(null)?.path

        val mMusicCenter = IMusicContentCenterEx.create(RtcEngineController.rtcEngine)!!
        mMusicCenter.initialize(contentCenterConfiguration)

        val ktvApiConfig = KTVApiConfig(
            BuildConfig.AGORA_APP_ID,
            mMusicCenter,
            RtcEngineController.rtcEngine,
            KeyCenter.channelId,             // 演唱频道channelId
            KeyCenter.localUid,              // uid
            2023,                  // mpk uid
            KeyCenter.channelId  + "_ad", // 观众频道channelId
            RtcEngineController.chorusChannelToken,        // 演唱频道channelId + uid = 加入演唱频道的token
            RtcEngineController.musicStreamToken,          // 演唱频道channelId + mpk uid = mpk 流加入频道的token
            10,
            KTVType.Cantata,
            false,
            KTVMusicType.SONG_CODE,
            0
        )
        ktvApi.initialize(ktvApiConfig)
        ktvApi.addEventHandler(ktvApiEventHandler)
        ktvApi.setLrcView(object : ILrcView {
            override fun onUpdateProgress(progress: Long, position: Long) {
                karaokeView?.setProgress(progress)
            }

            override fun onDownloadLrcData(url: String?) {
                url?.let {
                    val mLyricsModel = KaraokeView.parseLyricData(File(it), null)
                    if (mLyricsModel != null) {
                        karaokeView?.setLyricData(mLyricsModel);
                    }
                }
            }
        })
    }
}