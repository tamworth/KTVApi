# K 歌场景化 API 示例 demo

> 本文档主要介绍如何快速跑通 K 歌场景化 API 示例工程，本 demo 支持普通合唱、大合唱两种模式, 包含加载、播放声网内容中心版权音乐和本地音乐文件等功能
>
> **Demo 效果:**
>
> <img src="https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ktv/ktvapi_demo3.jpg" width="300" height="640"><img src="https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ktv/ktvapi_demo4.jpg" width="300" height="640">
---

## 1. 环境准备

- <mark>最低兼容 Android 5.0</mark>（SDK API Level 21）
- Android Studio 3.5及以上版本
- Android 5.0 及以上的手机设备

---

## 2. 运行示例

- 2.1 进入声网控制台获取 APP ID 和 APP 证书 [控制台入口](https://console.shengwang.cn/overview)

  - 点击创建项目

    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_1.jpg)

  - 选择项目基础配置, 鉴权机制需要选择**安全模式**

    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_2.jpg)

  - 拿到项目 APP ID 与 APP 证书

    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_3.jpg)
    
  - **Restful API 服务配置（大合唱）**
    ```json
    注: 体验大合唱模式需要填写 Restful API 相关信息
    ```
    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_4.jpg)
    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_5.jpg)
    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_6.jpg)

  - **联系声网技术支持给 APP ID 开通 K 歌歌单权限和云端转码权限([声网支持](https://ticket.shengwang.cn/form?type_id=&sdk_product=&sdk_platform=&sdk_version=&current=0&project_id=&call_id=&channel_name=))**

    ```json
    注: 拉取声网版权榜单、歌单、歌曲、歌词等功能是需要开通歌单权限的, 仅体验本地音乐文件模式可以不用开通
        体验大合唱模式需要开通云端转码权限, 仅体验普通合唱可以不用开通
    ```

- 2.2 在项目的 [**gradle.properties**](gradle.properties) 里填写需要的声网 App ID 和 App 证书、RESTFUL KEY 和 SECRET

  ```
  # RTM RTC SDK key Config
  AGORA_APP_ID：声网 APP ID
  AGORA_APP_CERTIFICATE：声网 APP 证书
  RESTFUL_API_KEY：声网RESTful API key
  RESTFUL_API_SECRET：声网RESTful API secret
  ```
- 2.3 用 Android Studio 运行项目即可开始您的体验

---

## 3. 如何集成场景化 API 实现 K 歌场景(音速达)

1、初始化 KTVAPI 需要传入 mccEx 对象
~~~kotlin
// ------------------ 初始化内容中心 ------------------
val contentCenterConfiguration = MusicContentCenterExConfiguration()
contentCenterConfiguration.context = context
contentCenterConfiguration.vendorConfigure = YsdVendorConfigure(
    appId = BuildConfig.YSD_APP_ID,
    appKey = BuildConfig.YSD_APP_Key,
    token = BuildConfig.YSD_APP_TOKEN,
    userId = BuildConfig.YSD_USERID,
    deviceId = "Device ID",
    chargeMode = ChargeMode.ONCE,
    urlTokenExpireTime = 60 * 15
)
contentCenterConfiguration.enableLog = true
contentCenterConfiguration.enableSaveLogToFile = true
contentCenterConfiguration.logFilePath = context?.getExternalFilesDir(null)?.path

val mMusicCenter = IMusicContentCenterEx.create(RtcEngineController.rtcEngine)!!
mMusicCenter.initialize(contentCenterConfiguration)

// ------------------ 初始化 KTVAPI ------------------
val ktvapi = GiantChorusKTVApiImpl()
val ktvApiConfig = KTVApiConfig(
    BuildConfig.AGORA_APP_ID,
    mMusicCenter, // 传入mMusicCenter对象
    RtcEngineController.rtcEngine,
    KeyCenter.channelId,             // 演唱频道channelId
    KeyCenter.localUid,              // uid
    MPKUID,                          // mpk uid
    KeyCenter.channelId  + "_ad",    // 观众频道channelId
    RtcEngineController.chorusChannelToken,        // 演唱频道channelId + uid = 加入演唱频道的token
    RtcEngineController.musicStreamToken,          // 演唱频道channelId + mpk uid = mpk 流加入频道的token
    10,
    KTVType.Cantata,åå
    false,
    KTVMusicType.SONG_CODE,
    0
)
ktvApi.initialize(ktvApiConfig)
~~~

2、注册歌词组件对象
~~~kotlin
// karaokeView 为歌词组件UI对象
ktvApi.setLrcView(object : ILrcView {
    // 进度
    override fun onUpdateProgress(progress: Long, position: Long) {
        karaokeView?.setProgress(progress)
    }

    // 音高线
    override fun onPitch(songCode: Long, data: RawScoreData) {
        karaokeView?.setPitch(data.speakerPitch, data.progressInMs)
    }

    // 单句打分
    override fun onLineScore(songCode: Long, value: LineScoreData) {

    }

    // 歌词和音高文件
    override fun onDownloadLrcData(lyricPath: String?, pitchPath: String?) {
        lyricPath?.let { lrc ->
            pitchPath?.let { pitch ->
                val mLyricsModel = KaraokeView.parseLyricData(File(lrc), File(pitch))
                if (mLyricsModel != null) {
                    karaokeView?.setLyricData(mLyricsModel)
                }
            }
        }
    }
})
~~~

3、加载播放歌曲
~~~kotlin
// --------------- 主唱&合唱 -----------------
// 使用声网版权中心歌单
val musicConfiguration = KTVLoadMusicConfiguration(
    歌曲唯一ID, 
    false, 
    KeyCenter.LeadSingerUid, // 主唱UID
    KTVLoadMusicMode.LOAD_MUSIC_AND_LRC, // 加载歌曲和歌词
    true // 是否需要获取音高文件, 不渲染音高线的场景传false
)
ktvApi.loadMusic(
  音速达songID, 
  musicConfiguration, 
  object : IMusicLoadStateListener {
    // 下载成功
    override fun onMusicLoadSuccess(songCode: Long, lyricUrl: String) {
        // 开启打分, 如果不需要打分可以取消这一步
        ktvApi.startScore(KeyCenter.songCode) { _, state, _ ->
            // 成功开启打分
            if (state == MccExState.START_SCORE_STATE_COMPLETED) {
                // 切换身份成领唱
                if (KeyCenter.isLeadSinger()) {
                    ktvApi.switchSingerRole(KTVSingRole.LeadSinger, object : ISwitchRoleStateListener {
                        override fun onSwitchRoleSuccess() {
                            // 切换身份成功开始播放音乐, 这里的songCode要传 loadMusicSuccess回调返回的
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
                // 切换身份成合唱, 合唱不用主动调用startSing就可以开始播放音乐!!!
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

    // 下载失败
    override fun onMusicLoadFail(songCode: Long, reason: KTVLoadSongFailReason) {
        Log.d("Music", "onMusicLoadFail, songCode: $songCode, reason: $reason")
    }

    // 下载进度
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

// --------------- 观众 -----------------
// 使用声网版权中心歌单
val musicConfiguration = KTVLoadMusicConfiguration(
    歌曲唯一ID, 
    false, 
    KeyCenter.LeadSingerUid, // 主唱UID
    KTVLoadMusicMode.LOAD_LRC_ONLY, // 观众只加载歌词
    false // 是否需要获取音高文件, 不渲染音高线的场景传false
)
ktvApi.loadMusic(
  音速达songID, 
  musicConfiguration, 
  object : IMusicLoadStateListener {
    // 下载成功
    override fun onMusicLoadSuccess(songCode: Long, lyricUrl: String) {
        
    }

    // 下载失败
    override fun onMusicLoadFail(songCode: Long, reason: KTVLoadSongFailReason) {
        Log.d("Music", "onMusicLoadFail, songCode: $songCode, reason: $reason")
    }

    // 下载进度
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
~~~



## 4. FAQ
- 集成遇到困难，该如何联系声网获取协助
  - 方案1：可以从智能客服获取帮助或联系技术支持人员 [声网支持](https://ticket.shengwang.cn/form?type_id=&sdk_product=&sdk_platform=&sdk_version=&current=0&project_id=&call_id=&channel_name=)
  - 方案2：加入微信群提问
  
    ![](https://download.agora.io/demo/release/SDHY_QA.jpg)
