package io.agora.ktvdemo.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import io.agora.karaoke_view.v11.KaraokeView
import io.agora.ktvdemo.BuildConfig
import io.agora.ktvdemo.RtcEngineController
import io.agora.ktvdemo.databinding.FragmentLivingBinding
import io.agora.ktvdemo.ktvapi.*
import io.agora.ktvdemo.utils.DownloadUtils
import io.agora.ktvdemo.utils.KeyCenter
import io.agora.ktvdemo.utils.ZipUtils
import io.agora.mediaplayer.Constants
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.RtcEngine
import java.io.File

class LivingFragment : BaseFragment<FragmentLivingBinding>() {

    private var karaokeView: KaraokeView? = null

    private val ktvApi: KTVApi by lazy {
        createKTVApi()
    }

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLivingBinding {
        return FragmentLivingBinding.inflate(inflater)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initView()
        initKTVApi()
        joinChannel()
    }

    private fun initView() {
        binding?.apply {
            karaokeView = KaraokeView(lyricsView,null)
            btnClose.setOnClickListener {
                findNavController().popBackStack()
            }
            if (KeyCenter.isLeadSinger()) {
                tvSinger.text = "主唱"
            } else if (KeyCenter.isCoSinger()) {
                tvSinger.text = "伴唱"
            } else {
                tvSinger.text = "观众"
            }
        }
    }

    private fun joinChannel() {
        val channelMediaOptions = ChannelMediaOptions().apply {
            autoSubscribeAudio = true
            clientRoleType = if (KeyCenter.isAudience()) io.agora.rtc2.Constants.CLIENT_ROLE_AUDIENCE
            else io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER
            autoSubscribeVideo = false
            autoSubscribeAudio = false
            publishCameraTrack = false
            publishMicrophoneTrack = !KeyCenter.isAudience()
        }
        RtcEngineController.rtcEngine.joinChannel(
            RtcEngineController.rtcToken,
            KeyCenter.channelId,
            KeyCenter.localUid,
            channelMediaOptions
        )
    }

    private fun initKTVApi() {
        val ktvApiConfig = KTVApiConfig(
            BuildConfig.AGORA_APP_ID,
            RtcEngineController.rtmToken,
            RtcEngineController.rtcEngine,
            KeyCenter.channelId,
            KeyCenter.localUid,
            "${KeyCenter.channelId}_ex",
            RtcEngineController.chorusChannelRtcToken,
            10,
            KTVType.Normal,
            if (KeyCenter.isMcc) KTVSongType.SONG_CODE else KTVSongType.SONG_URL
        )
        ktvApi.initialize(ktvApiConfig)
        ktvApi.addEventHandler(ktvApiEventHandler)
        ktvApi.renewInnerDataStreamId()
        ktvApi.setLrcView(object : ILrcView {
            override fun onUpdatePitch(pitch: Float?) {
            }

            override fun onUpdateProgress(progress: Long?) {
                if (progress != null) {
                    karaokeView?.setProgress(progress)
                }
            }

            override fun onDownloadLrcData(url: String?) {
                if (url != null) {
                    dealDownloadLrc(url)
                }
            }

            override fun onHighPartTime(highStartTime: Long, highEndTime: Long) {
            }
        })
        if (KeyCenter.isMcc) {
            val musicConfiguration = KTVLoadMusicConfiguration(
                KeyCenter.songCode.toString(), false, KeyCenter.LeadSingerUid,
                if (KeyCenter.isAudience()) KTVLoadMusicMode.LOAD_LRC_ONLY else KTVLoadMusicMode.LOAD_MUSIC_AND_LRC
            )
            ktvApi.loadMusic(KeyCenter.songCode, musicConfiguration, object : IMusicLoadStateListener {
                override fun onMusicLoadSuccess(songCode: Long, lyricUrl: String) {
                    if (KeyCenter.isLeadSinger()) {
                        ktvApi.switchSingerRole(KTVSingRole.LeadSinger, object : ISwitchRoleStateListener {
                            override fun onSwitchRoleSuccess() {
                                ktvApi.startSing(KeyCenter.songCode, 0)
                            }

                            override fun onSwitchRoleFail(reason: SwitchRoleFailReason) {

                            }
                        })
                    } else if (KeyCenter.isCoSinger()) {
                        ktvApi.switchSingerRole(KTVSingRole.CoSinger, object : ISwitchRoleStateListener {
                            override fun onSwitchRoleSuccess() {

                            }

                            override fun onSwitchRoleFail(reason: SwitchRoleFailReason) {

                            }
                        })
                    }
                }

                override fun onMusicLoadFail(songCode: Long, reason: KTVLoadSongFailReason) {

                }

                override fun onMusicLoadProgress(
                    songCode: Long,
                    percent: Int,
                    status: MusicLoadStatus,
                    msg: String?,
                    lyricUrl: String?
                ) {

                }
            })
        } else {
            val musicConfiguration = KTVLoadMusicConfiguration(
                KeyCenter.songCode.toString(), false, KeyCenter.LeadSingerUid, KTVLoadMusicMode.LOAD_NONE
            )
            val songPath = requireActivity().filesDir.absolutePath + File.separator
            val songName = "成都"
            ktvApi.loadMusic("$songPath$songName.mp3", musicConfiguration)
            val fileLrc = File("$songPath$songName.xml")
            val lyricsModel = KaraokeView.parseLyricsData(fileLrc)
            karaokeView?.lyricsData = lyricsModel
            if (KeyCenter.isLeadSinger()) {
                ktvApi.switchSingerRole(KTVSingRole.LeadSinger, object : ISwitchRoleStateListener {
                    override fun onSwitchRoleSuccess() {
                        ktvApi.startSing("$songPath$songName.mp3", 0)
                    }

                    override fun onSwitchRoleFail(reason: SwitchRoleFailReason) {

                    }
                })
            } else if (KeyCenter.isCoSinger()) {
                ktvApi.switchSingerRole(KTVSingRole.CoSinger, object : ISwitchRoleStateListener {
                    override fun onSwitchRoleSuccess() {

                    }

                    override fun onSwitchRoleFail(reason: SwitchRoleFailReason) {

                    }
                })
            }
        }
    }

    private fun dealDownloadLrc(url: String) {
        DownloadUtils.getInstance().download(requireContext(), url, object : DownloadUtils.FileDownloadCallback {
            override fun onSuccess(file: File) {
                if (file.name.endsWith(".zip")) {
                    ZipUtils.unzipOnlyPlainXmlFilesAsync(file.absolutePath,
                        file.absolutePath.replace(".zip", ""),
                        object : ZipUtils.UnZipCallback {
                            override fun onFileUnZipped(unZipFilePaths: MutableList<String>) {
                                var xmlPath = ""
                                for (path in unZipFilePaths) {
                                    if (path.endsWith(".xml")) {
                                        xmlPath = path
                                        break
                                    }
                                }

                                if (TextUtils.isEmpty(xmlPath)) {
                                    toast("The xml file not exist!")
                                    return
                                }

                                val xmlFile = File(xmlPath)
                                val lyricsModel = KaraokeView.parseLyricsData(xmlFile)

                                if (lyricsModel == null) {
                                    toast("Unexpected content from $xmlPath")
                                    return
                                }
                                karaokeView?.lyricsData = lyricsModel
                            }

                            override fun onError(e: Exception?) {
                                toast(e?.message ?: "UnZip xml error")
                            }

                        }
                    )
                } else {
                    val lyricsModel = KaraokeView.parseLyricsData(file)
                    if (lyricsModel == null) {
                        toast("Unexpected content from $file")
                        return
                    }
                    karaokeView?.lyricsData = lyricsModel
                }
            }

            override fun onFailed(exception: Exception?) {
                toast("download lrc  ${exception?.message}")
            }
        })
    }

    private val ktvApiEventHandler = object : IKTVApiEventHandler() {
        override fun onMusicPlayerStateChanged(
            state: Constants.MediaPlayerState,
            error: Constants.MediaPlayerError,
            isLocal: Boolean
        ) {
            super.onMusicPlayerStateChanged(state, error, isLocal)
        }

        override fun onSingerRoleChanged(oldRole: KTVSingRole, newRole: KTVSingRole) {
            super.onSingerRoleChanged(oldRole, newRole)
        }

        override fun onTokenPrivilegeWillExpire() {
            super.onTokenPrivilegeWillExpire()

        }
    }

    override fun onDestroy() {
        ktvApi.switchSingerRole(KTVSingRole.Audience, null)
        ktvApi.removeEventHandler(ktvApiEventHandler)
        ktvApi.release()
        super.onDestroy()
    }
}