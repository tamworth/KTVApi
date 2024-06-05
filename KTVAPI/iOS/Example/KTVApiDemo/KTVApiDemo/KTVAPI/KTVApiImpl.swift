//
//  KTVApiImpl.swift
//  AgoraEntScenarios
//
//  Created by wushengtao on 2023/3/14.
//

import Foundation
import AgoraRtcKit
import AgoraMccExService
#if DEBUG
#warning("测试需要")
func TWLog(_ msg: String) {
    print(msg)
}

func printLog(message: String) {
    print(message)
}

class TestAAA {
    var data: TestData? = TestData()
}

class TestData {
    var typeid: Int = 492
}

extension Dictionary {
    func toJsonStr() -> String? {
        return "\(self)"
    }
}

enum ErrorLevel {
    case info
    case warning
    case error
}

class Logger {
    static func log(_ obj: Any, message: String, level: ErrorLevel) {
        TWLog(message)
    }
}

var socketInfoItems: TestAAA? = TestAAA()
#else
import TWFoundation
import TWUIKit
import TWCore
import SwiftyUserDefaults

/// 当前ktv音量
let currentKTVVolum = DefaultsKey<Int>("currentKTVVolum", defaultValue:80)
#endif

/// 加载歌曲状态
@objc public enum KTVLoadSongState: Int {
    case idle = -1      //空闲
    case ok = 0         //成功
    case failed         //失败
    case inProgress    //加载中
}

enum KTVSongMode: Int {
    case songCode
    case songUrl
}

private func agoraPrint(_ message: String) {
//    KTVLog.info(text: message, tag: "KTVApi")
    TWLog("KTVApi ---------\(message)")
}

class KTVApiImpl: NSObject{
    
    var apiConfig: KTVApiConfig?
    
    public var forward: Int = 0

    private var songConfig: KTVSongConfiguration?
    /// 人声的connection
    private var subChorusConnection: AgoraRtcConnection?

    private var eventHandlers: NSHashTable<AnyObject> = NSHashTable<AnyObject>.weakObjects()
    private var loadMusicListeners: NSMapTable<NSString, AnyObject> = NSMapTable<NSString, AnyObject>(keyOptions: .copyIn, valueOptions: .weakMemory)

    private var musicPlayer: AgoraMusicPlayerProtocolEx?
    public var mcc: AgoraMusicContentCenterEx?

    private var loadSongMap = Dictionary<String, KTVLoadSongState>()
//    private var lyricUrlMap = Dictionary<String, String>()
    private var loadDict = Dictionary<String, KTVLoadSongState>()
    private var lyricCallbacks = Dictionary<String, LyricCallback>()
    private var pitchCallbacks = Dictionary<String, LyricCallback>()
    private var musicCallbacks = Dictionary<String, LoadMusicCallback>()
    private var scoreCallbacks = Dictionary<String, ScoreCallback>()
    
    private var hasSendPreludeEndPosition: Bool = false
    private var hasSendEndPosition: Bool = false
   
    private var audioPlayoutDelay: NSInteger = 0
    private var isNowMicMuted: Bool = false
    private var loadSongState: KTVLoadSongState = .idle
    private var lastNtpTime: Int = 0
    
    private var playerState: AgoraMediaPlayerState = .idle {
        didSet {
            agoraPrint("playerState did changed: \(oldValue.rawValue)->\(playerState.rawValue)")
            updateRemotePlayBackVolumeIfNeed()
            updateTimer(with: playerState)
        }
    }
    private var pitch: Double = 0
    private var localPlayerPosition: TimeInterval = 0
    var remotePlayerPosition: TimeInterval = 0
    var remotePlayerDuration: TimeInterval = 0
    private var localPlayerSystemTime: TimeInterval = 0
    private var lastMainSingerUpdateTime: TimeInterval = 0
    private var playerDuration: TimeInterval = 0
    /// 上次设置给歌词组件的pos
    var mLastSetPlayPosTime: TimeInterval = 0

    private var musicChartDict: [String: MusicChartCallBacks] = [:]
    private var musicSearchDict: Dictionary<String, MusicResultCallBacks> = Dictionary<String, MusicResultCallBacks>()
    private var onJoinExChannelCallBack : JoinExChannelCallBack?
    private var mainSingerHasJoinChannelEx: Bool = false
    private var dataStreamId: Int = 0
    var lastReceivedPosition: TimeInterval = 0
    private var localPosition: Int = 0
    
    private var songMode: KTVSongMode = .songCode
    private var useCustomAudioSource:Bool = false
    private var songUrl: String = ""
    private var songCode: Int = 0
    private var songIdentifier: String = ""
    private var innerAudienceChannelStreamId: Int = 0
    private var mpkConnection: AgoraRtcConnection? = nil
    // 音频路由
    private var audioRoute = -1

    // 演唱分数
    private var singingScore = 0
    // ------------------ 评分驱动混音同步 ------------------
    private var mStopSyncScore = true
    
    // ------------------ 云端合流信息同步 ------------------
    private var mStopSyncCloudConvergenceStatus = true
    
    // 开始发送分数
    private var syncScoreTimer: Timer?
    // 开始发送分数
    private var mSyncCloudConvergenceStatusFuture: Timer?
    
    var joinSubSuccessBlock: ISwitchRoleStateListener?
    
    private lazy var mSyncCloudConvergenceStatusTask = DispatchWorkItem {[weak self] in
        guard let isRoomOwner = self?.apiConfig?.isRoomOwner else { return }
        if self?.mStopSyncCloudConvergenceStatus == false { return }
        
        if isRoomOwner {
            self?.sendSyncCloudConvergenceStatus()
        }
    }

    private var singerRole: KTVSingRole = .audience {
        didSet {
            agoraPrint("singerRole changed: \(oldValue.rawValue)->\(singerRole.rawValue)")
        }
    }
    private var lrcControl: KTVLrcViewDelegate?
    
    private var timer: Timer?
    private var isPause: Bool = false
    
    public var remoteVolume: Int = 100
    private var joinChorusNewRole: KTVSingRole = .audience
    private var oldPitch: Double = 0
    private var isRelease: Bool = false
    private var recvFromDataStream = false
    private var startHighTime: Int = 0
    deinit {
        mcc?.register(eventDelegate: nil)
        mcc?.register(scoreDelegate: nil)
        agoraPrint("deinit KTVApiImpl")
    }

    @objc required init(config: KTVApiConfig) {
        super.init()
        agoraPrint("init KTVApiImpl")
        self.apiConfig = config
        setParams()

        // ------------------ 初始化内容中心 ------------------
        mcc = config.mcc
        mcc?.register(eventDelegate: self)
        mcc?.register(scoreDelegate: self)
        // ------------------ 初始化音乐播放器实例 ------------------
        musicPlayer = mcc?.createMusicPlayer(with: self)
        musicPlayer?.adjustPlayoutVolume(50)
        musicPlayer?.adjustPublishSignalVolume(50)

        #if DEBUG
        #warning("测试需要")
        var ktvVolum: Int32 = 100
        #else
        // 音量最佳实践调整
        var ktvVolum: Int32 = Int32(Defaults[currentKTVVolum])
        #endif
        // KTV歌房默认音量设置为100
        if socketInfoItems?.data?.typeid == 492 {
            ktvVolum = 100
        }
        musicPlayer?.adjustPlayoutVolume(ktvVolum)
        musicPlayer?.adjustPublishSignalVolume(ktvVolum)
        musicPlayer?.setPlayerOption("play_pos_change_callback", value: 100)
        initTimer()
    }
    
    private func setParams() {
        guard let engine = self.apiConfig?.engine else {return}
        
        engine.setParameters("{\"rtc.use_audio4\": true}")
        engine.setParameters("{\"rtc.ntp_delay_drop_threshold\": 1000}")
        engine.setParameters("{\"rtc.video.enable_sync_render_ntp\": true}")
        engine.setParameters("{\"rtc.net.maxS2LDelay\": 800}")
        engine.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\": true}")
        engine.setParameters("{\"rtc.net.maxS2LDelayBroadcast\": 400}")
        engine.setParameters("{\"che.audio.neteq.prebuffer\": true}")
        engine.setParameters("{\"che.audio.neteq.prebuffer_max_delay\": 600}")
        engine.setParameters("{\"che.audio.max_mixed_participants\": 8}")
        engine.setParameters("{\"che.audio.custom_bitrate\": 48000}")
        engine.setParameters("{\"che.audio.direct.uplink_process\": false}")
        engine.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
        engine.setParameters("{\"che.audio.neteq.targetlevel_offset\": 20}")
        engine.setParameters("{\"che.audio.direct.uplink_process\": false}")
        engine.setParameters("{\"rtc.use_audio4\": true}")
    }
    
    func renewInnerDataStreamId() {
        let dataStreamConfig = AgoraDataStreamConfig()
        dataStreamConfig.ordered = false
        dataStreamConfig.syncWithAudio = true
        self.apiConfig?.engine?.createDataStream(&dataStreamId, config: dataStreamConfig)
    }
}

//MARK: KTVApiDelegate
extension KTVApiImpl: KTVApiDelegate {
    func setSingingScore(score: Int) {
        singingScore = score
    }

    func getMusicContentCenter() -> AgoraMusicContentCenterEx? {
        return mcc
    }
    
    func setLrcView(view: KTVLrcViewDelegate) {
        lrcControl = view
    }
    
    func setLrcProgress(data: Data?) {
        guard let messageData = data else {
            return
        }
        
        do {
            let jsonMsg = try JSONSerialization.jsonObject(with: messageData, options: []) as? [String: Any]
            
            guard let cmd = jsonMsg?["cmd"] as? String else {
                return
            }
            
            if cmd == "setLrcTime" {
                if let realPosition = jsonMsg?["realTime"] as? Int,
                   let songId = jsonMsg?["songIdentifier"] as? String {
                    var recvTimeOff: TimeInterval = Date().timeIntervalSince1970 - lastReceivedPosition
                    var mpkPosDiff: TimeInterval = remotePlayerPosition - recvTimeOff
                    if socketInfoItems?.data?.typeid != 492 {
                        if lastReceivedPosition != 0 && (recvTimeOff - mpkPosDiff) > 3000 || mpkPosDiff < 0 || (mLastSetPlayPosTime - remotePlayerDuration) > 1000 { return }
                    }
                    // 观众
                    if songIdentifier == songId {
                        lastReceivedPosition = Date().timeIntervalSince1970
                        remotePlayerPosition = TimeInterval(realPosition)
                    } else {
                        printLog(message: "加入合唱08")
                        lastReceivedPosition = 0
                        remotePlayerPosition = 0
                    }
                }
            }
        } catch {
            agoraPrint("onStreamMessage: \(error)")
        }
    }

    func loadMusic(songCode: Int, config: KTVSongConfiguration, onMusicLoadStateListener: IMusicLoadStateListener) {
        let _songCode = mcc?.getInternalSongCode("\(songCode)", jsonOption: nil) ?? 0
        agoraPrint("loadMusic songCode:\(_songCode) ")
        printLog(message: "加入合唱06")
        self.songMode = .songCode
        self.songCode = _songCode
        self.songIdentifier = config.songIdentifier
        self.localPlayerPosition = 0
        self.remotePlayerPosition = 0
        self.mLastSetPlayPosTime = 0
        _loadMusic(config: config, mode: config.mode, onMusicLoadStateListener: onMusicLoadStateListener)
    }
    
    func loadMusic(config: KTVSongConfiguration, url: String) {
        self.songMode = .songUrl
        self.songUrl = url
        self.songIdentifier = config.songIdentifier
        if config.autoPlay {
            // 主唱自动播放歌曲
//            if singerRole != .leadSinger {
//                switchSingerRole(newRole: .soloSinger) { _, _ in
//
//                }
//            }
            startSing(url: url, startPos: 0)
        }
    }
    
    func startScore(songCode: Int, callback: @escaping ScoreCallback) {
        let _songCode = mcc?.getInternalSongCode("\(songCode)", jsonOption: nil) ?? 0
        scoreCallbacks.updateValue(callback, forKey: String(_songCode))
        mcc?.startScore(_songCode)
    }

    func getMediaPlayer() -> AgoraMusicPlayerProtocolEx? {
        return musicPlayer
    }

    func addEventHandler(ktvApiEventHandler: KTVApiEventHandlerDelegate) {
        if eventHandlers.contains(ktvApiEventHandler) {
            return
        }
        eventHandlers.add(ktvApiEventHandler)
    }

    func removeEventHandler(ktvApiEventHandler: KTVApiEventHandlerDelegate) {
        eventHandlers.remove(ktvApiEventHandler)
    }

    func cleanCache() {
        isRelease = true
        musicPlayer?.stop()
        freeTimer()
        agoraPrint("cleanCache")
        lrcControl = nil
        lyricCallbacks.removeAll()
        musicCallbacks.removeAll()
        scoreCallbacks.removeAll()
        onJoinExChannelCallBack = nil
        stopSyncCloudConvergenceStatus()
        stopSyncScore()
        singingScore = 0
        loadMusicListeners.removeAllObjects()
        apiConfig?.engine?.destroyMediaPlayer(musicPlayer)
        musicPlayer = nil
        mcc?.register(eventDelegate: nil)
        mcc?.register(scoreDelegate: nil)
        mcc = nil
        if let subChorusConnection = subChorusConnection {
            apiConfig?.engine?.setDelegateEx(nil,
                                             connection: subChorusConnection)
        }
        subChorusConnection = nil
        apiConfig?.engine = nil
        apiConfig = nil
        AgoraMusicContentCenter.destroy()
        self.eventHandlers.removeAllObjects()
        loadMusicListeners.removeAllObjects()
    }
    
    func renewToken(rtmToken: String, chorusChannelRtcToken: String) {
               // 更新RtmToken
       mcc?.renewToken(rtmToken)
           // 更新合唱频道RtcToken
           if let subChorusConnection = subChorusConnection {
               let channelMediaOption = AgoraRtcChannelMediaOptions()
               channelMediaOption.token = chorusChannelRtcToken
               apiConfig?.engine?.updateChannelEx(with: channelMediaOption, connection: subChorusConnection)
        }
    }

    func switchSingerRole(newRole: KTVSingRole, onSwitchRoleState: @escaping (KTVSwitchRoleState, KTVSwitchRoleFailReason) -> Void) {
        let oldRole = singerRole
        self.switchSingerRole(oldRole: oldRole, newRole: newRole, token: apiConfig?.chorusChannelToken ?? "", stateCallBack: onSwitchRoleState)
    }

    /**
     * 恢复播放
     */
    @objc public func resumeSing() {
        agoraPrint("resumeSing")
        if musicPlayer?.getPlayerState() == .paused {
            musicPlayer?.resume()
        } else {
            let ret = musicPlayer?.play()
            agoraPrint("resumeSing ret: \(ret ?? -1)")
        }
    }

    /**
     * 暂停播放
     */
    @objc public func pauseSing() {
        agoraPrint("pauseSing")
        musicPlayer?.pause()
    }

    /**
     * 调整进度
     */
    @objc public func seekSing(time: NSInteger) {
        agoraPrint("seekSing")
       musicPlayer?.seek(toPosition: time)
    }

    /**
     * 选择音轨，原唱、伴唱
     */
//    @objc public func selectPlayerTrackMode(mode: KTVPlayerTrackMode) {
//        apiConfig?.engine.selectAudioTrack(mode == .original ? 0 : 1)
//    }

    /**
     * 设置当前mic开关状态
     */
    @objc public func setMicStatus(isOnMicOpen: Bool) {
        self.isNowMicMuted = !isOnMicOpen
    }

    /**
     * 获取mpk实例
     */
    @objc public func getMusicPlayer() -> AgoraMusicPlayerProtocolEx? {
        return musicPlayer
    }
}

// 主要是角色切换，加入合唱，加入多频道，退出合唱，退出多频道
extension KTVApiImpl {
    private func switchSingerRole(oldRole: KTVSingRole, newRole: KTVSingRole, token: String, stateCallBack:@escaping ISwitchRoleStateListener) {
    //    agoraPrint("switchSingerRole oldRole: \(oldRole.rawValue), newRole: \(newRole.rawValue)")
        if oldRole == .audience && newRole == .soloSinger {
            // 1、KTVSingRoleAudience -》KTVSingRoleMainSinger
            singerRole = newRole
            becomeSoloSinger()
            getEventHander { delegate in
                delegate.onSingerRoleChanged(oldRole: .audience, newRole: .soloSinger)
            }
            
            stateCallBack(.success, .none)
        } else if oldRole == .audience && newRole == .leadSinger {
            becomeSoloSinger()
            if apiConfig?.ktvType == .Cantata {
                joinChorus(newRole: newRole)
                singerRole = newRole
                getEventHander { delegate in
                    delegate.onSingerRoleChanged(oldRole: .audience, newRole: .leadSinger)
                }
                stateCallBack(.success, .none)
            } else {
                joinChorus(role: newRole, token: token, joinExChannelCallBack: {[weak self] flag, status in
                    guard let self = self else {return}
                    //还原临时变量为观众
                    self.joinChorusNewRole = .audience
                    
                    if flag == true {
                        self.singerRole = newRole
                        stateCallBack(.success, .none)
                    } else {
                        self.leaveChorus(role: .leadSinger)
                        stateCallBack(.fail, .joinChannelFail)
                    }
                })
            }

        } else if oldRole == .soloSinger && newRole == .audience {
            stopSing()
            singerRole = newRole
            getEventHander { delegate in
                delegate.onSingerRoleChanged(oldRole: .soloSinger, newRole: .audience)
            }
            
            stateCallBack(.success, .none)
        } else if oldRole == .audience && newRole == .coSinger {
            if apiConfig?.ktvType == .Cantata {
                joinChorus(newRole: newRole)
                singerRole = newRole
                getEventHander { delegate in
                    delegate.onSingerRoleChanged(oldRole: .audience, newRole: .coSinger)
                }
                stateCallBack(.success, .none)
            } else {
                joinChorus(role: newRole, token: token, joinExChannelCallBack: {[weak self] flag, status in
                    guard let self = self else {return}
                    //还原临时变量为观众
                    self.joinChorusNewRole = .audience
                    if flag == true {
                        self.singerRole = newRole
                        //TODO(chenpan):如果观众变成伴唱，需要重置state，防止同步主唱state因为都是playing不会修改
                        //后面建议改成remote state(通过data stream获取)和local state(通过player didChangedToState获取)
                        self.playerState = self.musicPlayer?.getPlayerState() ?? .idle
                        self.getEventHander { delegate in
                            delegate.onSingerRoleChanged(oldRole: .audience, newRole: .coSinger)
                        }
                        stateCallBack(.success, .none)
                    } else {
                        self.leaveChorus(role: .coSinger)
                        stateCallBack(.fail, .joinChannelFail)
                    }
                })
            }
        } else if oldRole == .coSinger && newRole == .audience {
            if apiConfig?.ktvType == .Cantata {
                leaveChorus2(role: .coSinger)
            } else {
                leaveChorus(role: .coSinger)
            }
            singerRole = newRole
            getEventHander { delegate in
                delegate.onSingerRoleChanged(oldRole: .coSinger, newRole: .audience)
            }
            
            stateCallBack(.success, .none)
        } else if oldRole == .soloSinger && newRole == .leadSinger {
            if apiConfig?.ktvType == .Cantata {
                joinChorus(newRole: newRole)
                singerRole = newRole
                getEventHander { delegate in
                    delegate.onSingerRoleChanged(oldRole: .soloSinger, newRole: .leadSinger)
                }
                stateCallBack(.success, .none)
            } else {
                joinChorus(role: newRole, token: token, joinExChannelCallBack: {[weak self] flag, status in
                    guard let self = self else {return}
                    //还原临时变量为观众
                    self.joinChorusNewRole = .audience
                    if flag == true {
                        self.singerRole = newRole
                        self.getEventHander { delegate in
                            delegate.onSingerRoleChanged(oldRole: .soloSinger, newRole: .leadSinger)
                        }
                        stateCallBack(.success, .none)
                    } else {
                        self.leaveChorus(role: .leadSinger)
                        stateCallBack(.fail, .joinChannelFail)
                    }
                })
            }
        } else if oldRole == .leadSinger && newRole == .soloSinger {
            if apiConfig?.ktvType == .Cantata {
                leaveChorus2(role: .leadSinger)
            } else {
                leaveChorus(role: .leadSinger)
            }
            singerRole = newRole
            getEventHander { delegate in
                delegate.onSingerRoleChanged(oldRole: .leadSinger, newRole: .soloSinger)
            }
            
            stateCallBack(.success, .none)
        } else if oldRole == .leadSinger && newRole == .audience {
            if apiConfig?.ktvType == .Cantata {
                leaveChorus2(role: .leadSinger)
            } else {
                leaveChorus(role: .leadSinger)
            }
            stopSing()
            if socketInfoItems?.data?.typeid == 492 {
                self.pitch = 0
            }
            singerRole = newRole
            getEventHander { delegate in
                delegate.onSingerRoleChanged(oldRole: .leadSinger, newRole: .audience)
            }
            
            stateCallBack(.success, .none)
        } else {
            if socketInfoItems?.data?.typeid == 492 {
                if oldRole == .coSinger && newRole == .leadSinger {
                    if apiConfig?.ktvType == .Cantata {
                        joinChorus(newRole: newRole)
                        singerRole = newRole
                        getEventHander { delegate in
                            delegate.onSingerRoleChanged(oldRole: .coSinger, newRole: .leadSinger)
                        }
                        stateCallBack(.success, .none)
                    } else {
                        joinChorus(role: newRole, token: token, joinExChannelCallBack: {[weak self] flag, status in
                            guard let self = self else {return}
                            //还原临时变量为观众
                            self.joinChorusNewRole = .audience
                            
                            if flag == true {
                                self.singerRole = newRole
                                stateCallBack(.success, .none)
                            } else {
                                self.leaveChorus(role: .leadSinger)
                                stateCallBack(.fail, .joinChannelFail)
                            }
                        })
                    }
                } else if oldRole == .leadSinger && newRole == .coSinger {
                    if apiConfig?.ktvType == .Cantata {
                        leaveChorus2(role: .leadSinger)
                        stopSing()
                        self.pitch = 0
                        singerRole = newRole
                        getEventHander { delegate in
                            delegate.onSingerRoleChanged(oldRole: .leadSinger, newRole: .coSinger)
                        }
                        stateCallBack(.success, .none)
                    } else {
                        joinChorus(role: newRole, token: token, joinExChannelCallBack: {[weak self] flag, status in
                            guard let self = self else {return}
                            //还原临时变量为观众
                            self.joinChorusNewRole = .audience
                            
                            if flag == true {
                                self.singerRole = newRole
                                stateCallBack(.success, .none)
                            } else {
                                self.leaveChorus(role: .leadSinger)
                                stateCallBack(.fail, .joinChannelFail)
                            }
                        })
                    }
                } else {
                    stateCallBack(.fail, .noPermission)
                    agoraPrint("Error！You can not switch role from \(oldRole.rawValue) to \(newRole.rawValue)!")
                }
            } else {
                stateCallBack(.fail, .noPermission)
                agoraPrint("Error！You can not switch role from \(oldRole.rawValue) to \(newRole.rawValue)!")
            }
        }

    }

    private func becomeSoloSinger() {
        apiConfig?.engine?.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":false}")
        apiConfig?.engine?.setParameters("{\"che.audio.neteq.enable_stable_playout\":false}")
        apiConfig?.engine?.setParameters("{\"che.audio.custom_bitrate\": 80000}")
        apiConfig?.engine?.setAudioScenario(.chorus)
        agoraPrint("becomeSoloSinger")
        let mediaOption = AgoraRtcChannelMediaOptions()
        mediaOption.autoSubscribeAudio = true
        //mediaOption.autoSubscribeVideo = true
        mediaOption.publishMediaPlayerId = Int(musicPlayer?.getMediaPlayerId() ?? 0)
        mediaOption.publishMediaPlayerAudioTrack = true
        apiConfig?.engine?.updateChannel(with: mediaOption)
    }

    /**
     * 加入合唱
     */
    private func joinChorus(role: KTVSingRole, token: String, joinExChannelCallBack: @escaping JoinExChannelCallBack) {
        self.onJoinExChannelCallBack = joinExChannelCallBack
        if role == .leadSinger {
            agoraPrint("joinChorus: KTVSingRoleMainSinger")
            joinChorus2ndChannel(newRole: role, token: token)
        } else if role == .coSinger {
            
            let mediaOption = AgoraRtcChannelMediaOptions()
            mediaOption.autoSubscribeAudio = true
           // mediaOption.autoSubscribeVideo = true
            mediaOption.publishMediaPlayerAudioTrack = false
            apiConfig?.engine?.updateChannel(with: mediaOption)
            
            if self.songMode == .songCode {
                musicPlayer?.openMedia(songCode: self.songCode , startPos: 0)
            } else {
                musicPlayer?.open(self.songUrl, startPos: 0)
            }
            
            joinChorus2ndChannel(newRole: role, token: token)

        } else if role == .audience {
            agoraPrint("joinChorus fail!")
        }
    }

    private func joinChorus2ndChannel(newRole: KTVSingRole, token: String) {
        let role = newRole
        if role == .soloSinger || role == .audience {
            agoraPrint("joinChorus2ndChannel with wrong role")
            return
        }
        
        agoraPrint("joinChorus2ndChannel role: \(role.rawValue)")
        if newRole == .coSinger {
            apiConfig?.engine?.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":false}")
            apiConfig?.engine?.setParameters("{\"che.audio.neteq.enable_stable_playout\":false}")
            apiConfig?.engine?.setParameters("{\"che.audio.custom_bitrate\": 48000}")
            apiConfig?.engine?.setAudioScenario(.chorus)
        }

        let mediaOption = AgoraRtcChannelMediaOptions()
        // main singer do not subscribe 2nd channel
        // co singer auto sub
        mediaOption.autoSubscribeAudio = role != .leadSinger
      //  mediaOption.autoSubscribeVideo = false
        mediaOption.publishMicrophoneTrack = newRole == .leadSinger
        mediaOption.enableAudioRecordingOrPlayout = role != .leadSinger
        mediaOption.clientRoleType = .broadcaster
        mediaOption.parameters = "{\"rtc.use_audio4\": true}"
        let rtcConnection = AgoraRtcConnection()
        rtcConnection.channelId = apiConfig?.chorusChannelName ?? ""
        rtcConnection.localUid = UInt(apiConfig?.localUid ?? 0)
       subChorusConnection = rtcConnection

        joinChorusNewRole = role
       let ret = apiConfig?.engine?.joinChannelEx(byToken: token,
                                                  connection: rtcConnection,
                                                  delegate: self,
                                                  mediaOptions: mediaOption,
                                                  joinSuccess: nil)
        agoraPrint("joinChannelEx ret: \(ret ?? -999)")
        if newRole == .coSinger {
            let uid = UInt(songConfig?.mainSingerUid ?? 0)
            let ret =
            apiConfig?.engine?.muteRemoteAudioStream(uid, mute: true)
            agoraPrint("muteRemoteAudioStream: \(uid), ret: \(ret ?? -1)")
       }
        apiConfig?.engine?.setParameters("{\"rtc.use_audio4\": true}")
    }

    private func leaveChorus2ndChannel(_ role: KTVSingRole) {
        guard let config = songConfig else {return}
        guard let subConn = subChorusConnection else {return}
        if (role == .leadSinger) {
            apiConfig?.engine?.leaveChannelEx(subConn)
        } else if (role == .coSinger) {
            apiConfig?.engine?.leaveChannelEx(subConn)
            apiConfig?.engine?.muteRemoteAudioStream(UInt(config.mainSingerUid), mute: false)
        }
    }

    /**
     * 离开合唱
     */

    private func leaveChorus(role: KTVSingRole) {
        agoraPrint("leaveChorus role: \(singerRole.rawValue)")
        if role == .leadSinger {
            mainSingerHasJoinChannelEx = false
            leaveChorus2ndChannel(role)
        } else if role == .coSinger {
            musicPlayer?.stop()
            let mediaOption = AgoraRtcChannelMediaOptions()
            mediaOption.autoSubscribeAudio = true
         //   mediaOption.autoSubscribeVideo = false
            mediaOption.publishMediaPlayerAudioTrack = false
            apiConfig?.engine?.updateChannel(with: mediaOption)
            leaveChorus2ndChannel(role)
            apiConfig?.engine?.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")
            apiConfig?.engine?.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
            apiConfig?.engine?.setParameters("{\"che.audio.custom_bitrate\": 48000}")
            apiConfig?.engine?.setAudioScenario(.gameStreaming)
        } else if role == .audience {
            agoraPrint("joinChorus: KTVSingRoleAudience does not need to leaveChorus!")
        }
    }
#if DEBUG
#warning("测试需要移除")
#else
    ///所有人加入子频道
    func joinSubChannel(newRole: KTVSingRole,subToken: String,stateCallBack:@escaping ISwitchRoleStateListener) {
        let channelName = apiConfig?.chorusChannelName ?? ""
        TWRtcManager.shared().createRtcChannel(channelName)
        guard let options = TWAgoraTool.shared().agoraExTool.multiChannelDic[channelName] else { return }
        options.autoSubscribeAudio = true
        options.autoSubscribeVideo = false
        options.publishMicrophoneTrack = false
        let rtcConnection = AgoraRtcConnection()
        rtcConnection.channelId = apiConfig?.chorusChannelName ?? ""
        rtcConnection.localUid = UInt(apiConfig?.localUid ?? 0)
        subChorusConnection = rtcConnection
//        let ret = apiConfig?.engine?.joinChannelEx(byToken: subToken, connection: rtcConnection, delegate: self, mediaOptions: options, joinSuccess: { [weak self] channel, uid, elp in
//            TWLog("===========加入子频道成功")
//            stateCallBack(.success, .none)
//        })
        joinSubSuccessBlock = stateCallBack
        
        let ret = apiConfig?.engine?.joinChannelEx(byToken: subToken,
                                                   connection: rtcConnection,
                                                   delegate: self,
                                                   mediaOptions: options)
        if ret != 0 {
            agoraPrint("joinChannelEx fail ret: \(ret)")
            stateCallBack(.fail, .joinChannelFail)
        }
    }
#endif
    private func joinChorus(newRole: KTVSingRole) {
    agoraPrint("joinChorus: (newRole)")
    startSyncScore()
    switch newRole {
        case .leadSinger:
            // KTV歌房，声音延迟问题
            if socketInfoItems?.data?.typeid == 492 {
                apiConfig?.engine?.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":false}")
                apiConfig?.engine?.setParameters("{\"che.audio.neteq.enable_stable_playout\":false}")
                apiConfig?.engine?.setParameters("{\"che.audio.custom_bitrate\": 80000}")
                apiConfig?.engine?.setAudioScenario(.chorus)
            }
            let channelMediaOption = AgoraRtcChannelMediaOptions()
            channelMediaOption.autoSubscribeAudio = true
            channelMediaOption.publishMediaPlayerAudioTrack = false
            apiConfig?.engine?.updateChannel(with: channelMediaOption)
        
            startSyncCloudConvergenceStatus()
        
            let options = AgoraRtcChannelMediaOptions()
            options.autoSubscribeAudio = false
            options.autoSubscribeVideo = false
            options.publishMicrophoneTrack = false
            options.publishMediaPlayerAudioTrack = true
            options.publishMediaPlayerId = Int(musicPlayer?.getMediaPlayerId() ?? 0)
            options.clientRoleType = .broadcaster
            options.enableAudioRecordingOrPlayout = false
            
            let rtcConnection = AgoraRtcConnection()
            rtcConnection.channelId = apiConfig?.channelName ?? ""
            rtcConnection.localUid = UInt(apiConfig?.chorusUid ?? 0)
            mpkConnection = rtcConnection

        let ret = apiConfig?.engine?.joinChannelEx(byToken: apiConfig?.chorusChannelToken,
                                                   connection: rtcConnection,
                                                   delegate: self,
                                                   mediaOptions: options)
            //观众加入频道
//            let channelMediaOption2 = AgoraRtcChannelMediaOptions()
//            channelMediaOption2.publishMicrophoneTrack = true
//            channelMediaOption2.clientRoleType = .broadcaster
//            channelMediaOption2.enableAudioRecordingOrPlayout = false
//
//            let rtcConnection2 = AgoraRtcConnection()
//            rtcConnection2.channelId = apiConfig?.chorusChannelName ?? ""
//            rtcConnection2.localUid = UInt(apiConfig?.localUid ?? 0)
//            subChorusConnection = rtcConnection
//
//            let ret2 = apiConfig?.engine?.joinChannelEx(byToken: apiConfig?.chorusChannelToken, connection: rtcConnection2, delegate: self, mediaOptions: channelMediaOption2, joinSuccess: {[weak self] channel, uid, elapsed in
//                let config = AgoraDataStreamConfig()
//                config.ordered = false
//                config.syncWithAudio = true
//                guard let self = self else {return}
//                self.apiConfig?.engine?.createDataStreamEx(&self.innerAudienceChannelStreamId, config: config, connection: rtcConnection2)
//            })
            agoraPrint("joinChannelEx ret: \(ret ?? -999)")
        case .coSinger:
            apiConfig?.engine?.muteRemoteAudioStream(UInt(apiConfig?.chorusUid ?? 0), mute: true)
            apiConfig?.engine?.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":false}")
            apiConfig?.engine?.setParameters("{\"che.audio.neteq.enable_stable_playout\":false}")
            apiConfig?.engine?.setParameters("{\"che.audio.custom_bitrate\": 48000}")
            apiConfig?.engine?.setAudioScenario(.chorus)
    
            // 预加载歌曲成功
            if songMode == .songCode {
                musicPlayer?.openMedia(songCode: songCode, startPos: 0)
            } else {
                musicPlayer?.open(songUrl, startPos: 0)
            }
        default:
        agoraPrint("JoinChorus with Wrong role: \(singerRole)")
        }
    }

    private func leaveChorus2(role: KTVSingRole) {
    agoraPrint("leaveChorus: (singerRole)")
    stopSyncScore()
    switch role {
        case .leadSinger:
            mainSingerHasJoinChannelEx = false
            guard let subConn = subChorusConnection,let mpkConn = mpkConnection else {return}
            apiConfig?.engine?.leaveChannelEx(mpkConn)
//            apiConfig?.engine?.leaveChannelEx(subConn)
            stopSyncCloudConvergenceStatus()
            let channelMediaOption = AgoraRtcChannelMediaOptions()
            channelMediaOption.autoSubscribeAudio = true
            channelMediaOption.publishMediaPlayerId = Int(musicPlayer?.getMediaPlayerId() ?? 0)
            channelMediaOption.publishMediaPlayerAudioTrack = true
            apiConfig?.engine?.updateChannel(with: channelMediaOption)
        case .coSinger:
            musicPlayer?.stop()
            apiConfig?.engine?.muteRemoteAudioStream(UInt(apiConfig?.chorusUid ?? 0), mute: false)
            apiConfig?.engine?.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")
            apiConfig?.engine?.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
            apiConfig?.engine?.setParameters("{\"che.audio.custom_bitrate\": 48000}")
            apiConfig?.engine?.setAudioScenario(.gameStreaming)
        default:
            agoraPrint("JoinChorus with wrong role: \(singerRole)")
    }
    }
    
    func sendSyncScore() {
  
        var jsonObject: [String: Any] = [
            "service": "audio_smart_mixer",
            "version": "V1"
        ]
        
        var payload: [String: Any] = [:]
        payload["cname"] = apiConfig?.channelName
        payload["uid"] = String(apiConfig?.localUid ?? 0)
        payload["uLv"] = -1
        payload["specialLabel"] = 0
        payload["audioRoute"] = audioRoute
        payload["vocalScore"] = singingScore

        jsonObject["payload"] = payload

        agoraPrint("sendSyncScore jsonObject: \(jsonObject.toJsonStr() ?? "")")
        sendStreamMessageWithDict(jsonObject) { success in
            if success {
                agoraPrint("sendSyncScore success")
            } else {
                agoraPrint("sendSyncScore failed")
            }
        }
    }
    
    @objc private func handleSyncScoreTimer() {
        if mStopSyncScore == false {
            if singerRole == .leadSinger || singerRole == .coSinger {
                sendSyncScore()
            }
        }
    }

    func startSyncScore() {
        mStopSyncScore = false
        syncScoreTimer = Timer.scheduledTimer(timeInterval: 3.0,
                                              target: self,
                                              selector: #selector(handleSyncScoreTimer),
                                              userInfo: nil,
                                              repeats: true)
    }

    // 停止发送分数
    func stopSyncScore() {
        mStopSyncScore = true
        singingScore = 0

        syncScoreTimer?.invalidate()
        syncScoreTimer = nil
    }

    private func sendSyncCloudConvergenceStatus() {
        var jsonObject = [String: Any]()
        jsonObject["service"] = "audio_smart_mixer_status" // data message的目标消费者（服务）名
        jsonObject["version"] = "V1" // 协议版本号（而非服务版本号）

        var payloadJson = [String: Any]()
        payloadJson["Ts"] = getNtpTimeInMs() // NTP 时间
        payloadJson["cname"] = apiConfig?.channelName // 频道名
        payloadJson["status"] = getCloudConvergenceStatus() //（-1： unknown，0：非K歌状态，1：K歌播放状态，2：K歌暂停状态）
        payloadJson["bgmUID"] = "\(apiConfig?.chorusUid ?? -1)" // mpk流的uid
        payloadJson["leadsingerUID"] = "\(songConfig?.mainSingerUid ?? -1)" //（"-1" = unknown） //主唱Uid
        jsonObject["payload"] = payloadJson

        sendStreamMessageWithDict(jsonObject) { success in
            if success {
                agoraPrint("sendSyncCloudConvergenceStatus success")
            } else {
                agoraPrint("sendSyncCloudConvergenceStatus failed")
            }
        }
    }

    // -1： unknown，0：非K歌状态，1：K歌播放状态，2：K歌暂停状态）
    private func getCloudConvergenceStatus() -> Int {
        var status = -1
        switch playerState {
        case .playing:
            status = 1
        case .paused:
            status = 2
        default:
            status = 0
        }
        return status
    }
    
    @objc func handleMSyncCloudConvergenceStatusTask() {
        let isRoomOwner = apiConfig?.isRoomOwner
        if (!mStopSyncCloudConvergenceStatus) {
            if isRoomOwner == true {
                sendSyncCloudConvergenceStatus()
            }
        }
    }
    
    func startSyncCloudConvergenceStatus() {
        mStopSyncCloudConvergenceStatus = false
        mSyncCloudConvergenceStatusFuture = Timer.scheduledTimer(timeInterval: 0.2,
                                                                 target: self,
                                                                 selector: #selector(handleMSyncCloudConvergenceStatusTask),
                                                                 userInfo: nil,
                                                                 repeats: true)
    }

    // 停止发送分数
    func stopSyncCloudConvergenceStatus() {
        mStopSyncCloudConvergenceStatus = true

        mSyncCloudConvergenceStatusFuture?.invalidate()
        mSyncCloudConvergenceStatusFuture = nil
    }
    
    private func sendStreamMessageWithJsonObjectEx(_ dict: [String: Any], success: @escaping (Bool) -> Void) {
        guard let subConn = subChorusConnection, let messageData = compactDictionaryToData(dict as [String: Any]) else {return}
        let ret = apiConfig?.engine?.sendStreamMessageEx(innerAudienceChannelStreamId, data: messageData, connection: subConn)
        if ret == 0 {
            success(true)
        } else {
            agoraPrint("sendStreamMessageWithJsonObjectEx failed: \(String(describing: ret))")
        }
    }

}

extension KTVApiImpl {
    
    private func getEventHander(callBack:((KTVApiEventHandlerDelegate)-> Void)) {
        for obj in eventHandlers.allObjects {
            if obj is KTVApiEventHandlerDelegate {
                callBack(obj as! KTVApiEventHandlerDelegate)
            }
        }
    }
    
    private func _loadMusic(config: KTVSongConfiguration, mode: KTVLoadMusicMode, onMusicLoadStateListener: IMusicLoadStateListener){
        printLog(message: "加入合唱07")
        songConfig = config
        lastReceivedPosition = 0
        localPosition = 0
        
        if (config.mode == .loadNone) {
            return
        }
        
        if mode == .loadLrcOnly {
            loadLyricAndPitch(needPitch: config.needPitch, songCode: songCode) { [weak self] lyricPath, pitchPath in
                guard let self = self else { return }
                agoraPrint("loadLrcOnly: songCode:\(self.songCode) lyricPath:\(lyricPath ?? ""), pitchPath: \(pitchPath ?? "")")
//                if self.songCode != songCode {
//                    onMusicLoadStateListener.onMusicLoadFail(songCode: songCode, reason: .cancled)
//                    return
//                }
                if let lyricPath = lyricPath, !lyricPath.isEmpty {
//                    self.lyricUrlMap[String(self.songCode)] = lyricPath
                    self.lrcControl?.onDownloadLrcData(lrcPath: lyricPath, pitchPath: pitchPath)
                    onMusicLoadStateListener.onMusicLoadSuccess(songCode: self.songCode, lyricUrl: lyricPath)
                } else {
                    onMusicLoadStateListener.onMusicLoadFail(songCode: self.songCode, reason: .noLyricUrl)
                }
                
                if (config.autoPlay) {
                    // 伴唱自动播放歌曲
//                    if self.singerRole != .leadSinger {
//                        self.switchSingerRole(newRole: .soloSinger) { _, _ in
//
//                        }
//                    }
                    self.startSing(songCode: self.songCode, startPos: 0)
                }
            }
        } else {
            loadMusicListeners.setObject(onMusicLoadStateListener, forKey: "\(self.songCode)" as NSString)
            onMusicLoadStateListener.onMusicLoadProgress(songCode: self.songCode, percent: 0, status: .preloading, msg: "", lyricUrl: "")
            // TODO: 只有未缓存时才显示进度条
            if mcc?.isPreload(songCode) != 0 {
                onMusicLoadStateListener.onMusicLoadProgress(songCode: self.songCode, percent: 0, status: .preloading, msg: "", lyricUrl: "")
            }
            preloadMusic(with: songCode) { [weak self] status, songCode in
                guard let self = self else { return }
                if self.songCode != songCode {
                    onMusicLoadStateListener.onMusicLoadFail(songCode: songCode, reason: .cancled)
                    return
                }
                if status == .preloadOK {
                    if mode == .loadMusicAndLrc {
                        // 需要加载歌词
                        self.loadLyricAndPitch(needPitch: config.needPitch, songCode: songCode) { [weak self] lyricPath, pitchPath in
                            guard let self = self else { return }
                            agoraPrint("loadMusicAndLrc: songCode:\(songCode) status:\(status.rawValue) lyricPath:\(lyricPath ?? "") pitchPath: \(pitchPath ?? "")")
                            if self.songCode != songCode {
                                onMusicLoadStateListener.onMusicLoadFail(songCode: songCode, reason: .cancled)
                                return
                            }
                            if let lyricPath = lyricPath, !lyricPath.isEmpty {
//                                self.lyricUrlMap[String(songCode)] = lyricPath
                                self.lrcControl?.onDownloadLrcData(lrcPath: lyricPath, pitchPath: pitchPath)
                                onMusicLoadStateListener.onMusicLoadSuccess(songCode: songCode, lyricUrl: lyricPath)
                            } else {
                                onMusicLoadStateListener.onMusicLoadFail(songCode: songCode, reason: .noLyricUrl)
                            }
                            if config.autoPlay {
//                                if self.singerRole != .leadSinger {
//                                    self.switchSingerRole(newRole: .soloSinger) { _, _ in
//
//                                    }
//                                }
                                self.startSing(songCode: self.songCode, startPos: 0)
                            }
                        }
                    } else if mode == .loadMusicOnly {
                        agoraPrint("loadMusicOnly: songCode:\(songCode) load success")
                        if config.autoPlay {
                            // 主唱自动播放歌曲
//                            if self.singerRole != .leadSinger {
//                                self.switchSingerRole(newRole: .soloSinger) { _, _ in
//
//                                }
//                            }
                            self.startSing(songCode: self.songCode, startPos: 0)
                        }
                        onMusicLoadStateListener.onMusicLoadSuccess(songCode: songCode, lyricUrl: "")
                    }
                } else {
                    agoraPrint("load music failed songCode:\(songCode)")
                    onMusicLoadStateListener.onMusicLoadFail(songCode: songCode, reason: .musicPreloadFail)
                }
            }
        }
    }
    
    private func loadLyricAndPitch(needPitch: Bool, songCode: NSInteger, callBack:@escaping LyricAndPitchCallback) {
        agoraPrint("loadLyricAndPitch songCode: \(songCode)")
        var lyricPath: String? = nil
        var pitchPath: String? = nil
        
        func checkCompletion() {
            guard let lyricPath = lyricPath, let pitchPath = pitchPath else { return }
            callBack(lyricPath, pitchPath)
        }
        
        if needPitch {
            loadPitch(with: songCode) { pitchUrl in
                pitchPath = pitchUrl ?? ""
                checkCompletion()
            }
        } else {
            pitchPath = ""
            checkCompletion()
        }
        
        loadLyric(with: songCode) { lyricUrl in
            lyricPath = lyricUrl ?? ""
            checkCompletion()
        }
    }
    
    private func loadLyric(with songCode: NSInteger, callBack:@escaping LyricCallback) {
        agoraPrint("loadLyric songCode: \(songCode)")
        let requestId: String = self.mcc?.getLyric(songCode, lyricType: .KRC) ?? ""
        self.lyricCallbacks.updateValue(callBack, forKey: requestId)
    }
    
    private func loadPitch(with songCode: NSInteger, callBack:@escaping LyricCallback) {
        agoraPrint("loadPitch songCode: \(songCode)")
        let requestId: String = self.mcc?.getPitch(songCode) ?? ""
        self.pitchCallbacks.updateValue(callBack, forKey: requestId)
    }
    
    private func preloadMusic(with songCode: Int, callback: @escaping LoadMusicCallback) {
        agoraPrint("preloadMusic songCode: \(songCode)")
        if self.mcc?.isPreload(songCode) == 0 {
            musicCallbacks.removeValue(forKey: String(songCode))
            callback(.preloadOK, songCode)
            return
        }
//        let jsonOption = "{\"format\":{\"qualityLevel\":1}}"
        let requestId = self.mcc?.preload(songCode)
        if requestId?.isEmpty ?? false {
            musicCallbacks.removeValue(forKey: String(songCode))
            callback(.preloadError, songCode)
            return
        }
        musicCallbacks.updateValue(callback, forKey: String(songCode))
    }

    func startSing(songCode: Int, startPos: Int) {
        let _songCode = mcc?.getInternalSongCode("\(songCode)", jsonOption: nil) ?? 0
        let role = singerRole
        agoraPrint("startSing role: \(role.rawValue)")
        if self.songCode != _songCode {
            self.songMode = .songCode
            self.songCode = _songCode
            self.songIdentifier = "\(_songCode)"
        }
        
        let ret = musicPlayer?.openMedia(songCode: _songCode, startPos: startPos)
        agoraPrint("startSing->openMedia(\(_songCode) fail: \(ret ?? -1)")
    }
    
    func startSing(url: String, startPos: Int) {
        let role = singerRole
        agoraPrint("startSing role: \(role.rawValue)")
        if self.songUrl != songUrl {
            agoraPrint("startSing failed: canceled")
            return
        }
        apiConfig?.engine?.adjustPlaybackSignalVolume(Int(remoteVolume))
        let ret = musicPlayer?.open(url, startPos: 0)
        agoraPrint("startSing->openMedia(\(url) fail: \(ret ?? -1)")
    }

    /**
     * 停止播放歌曲
     */
    @objc public func stopSing() {
        agoraPrint("stopSing")

        let mediaOption = AgoraRtcChannelMediaOptions()
        mediaOption.autoSubscribeAudio = true
      //  mediaOption.autoSubscribeVideo = true
        mediaOption.publishMediaPlayerAudioTrack = false
        apiConfig?.engine?.updateChannel(with: mediaOption)

        if musicPlayer?.getPlayerState() != .stopped {
            musicPlayer?.stop()
        }
        if socketInfoItems?.data?.typeid == 492 {
            if singerRole == .audience {
                apiConfig?.engine?.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")
                apiConfig?.engine?.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
                apiConfig?.engine?.setParameters("{\"che.audio.custom_bitrate\": 48000}")
                apiConfig?.engine?.setAudioScenario(.gameStreaming)
            }
        } else {
            apiConfig?.engine?.setParameters("{\"rtc.video.enable_sync_render_ntp_broadcast\":true}")
            apiConfig?.engine?.setParameters("{\"che.audio.neteq.enable_stable_playout\":true}")
            apiConfig?.engine?.setParameters("{\"che.audio.custom_bitrate\": 48000}")
            apiConfig?.engine?.setAudioScenario(.gameStreaming)
        }
    }
    
    @objc public func setAudioPlayoutDelay(audioPlayoutDelay: Int) {
        self.audioPlayoutDelay = audioPlayoutDelay
    }

}

// rtc的代理回调
extension KTVApiImpl: AgoraRtcEngineDelegate {

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinChannel channel: String, withUid uid: UInt, elapsed: Int) {
        TWLog("===========加入子频道成功")
        joinSubSuccessBlock?(.success, .none)
        joinSubSuccessBlock = nil
        agoraPrint("didJoinChannel channel:\(channel) uid: \(uid)")
        if joinChorusNewRole == .leadSinger {
            mainSingerHasJoinChannelEx = true
            onJoinExChannelCallBack?(true, nil)
        }
        if joinChorusNewRole == .coSinger {
          self.onJoinExChannelCallBack?(true, nil)
        }
        if let subChorusConnection = subChorusConnection {
            apiConfig?.engine?.enableAudioVolumeIndicationEx(50, smooth: 10, reportVad: true, connection: subChorusConnection)
        }
#if DEBUG
#warning("测试需要，确认是否点点需要该功能")
        didJoinedOfUid(uid: uid, elapsed: elapsed)
#endif
    }
    
    func rtcEngine(_ engine: AgoraRtcEngineKit, didOccurError errorCode: AgoraErrorCode) {
        agoraPrint("didOccurError: \(errorCode.rawValue)")
        if errorCode != .joinChannelRejected {return}
        agoraPrint("join ex channel failed")
        engine.setAudioScenario(.gameStreaming)
        if joinChorusNewRole == .leadSinger {
            mainSingerHasJoinChannelEx = false
            onJoinExChannelCallBack?(false, .joinChannelFail)
        }

        if joinChorusNewRole == .coSinger {
            self.onJoinExChannelCallBack?(false, .joinChannelFail)
        }
    }
    
    //合唱频道的声音回调
    func rtcEngine(_ engine: AgoraRtcEngineKit, reportAudioVolumeIndicationOfSpeakers speakers: [AgoraRtcAudioVolumeInfo], totalVolume: Int) {
        let _ = speakers.map{
            let str = "ddddde正在说话的uid: \($0.uid)---" + "volume: \($0.volume)---"
            printLog(message: "--------------\(str)")
        }
        getEventHander { delegate in
            delegate.onChorusChannelAudioVolumeIndication(speakers: speakers, totalVolume: totalVolume)
        }
    }
    
    func rtcEngine(_ engine: AgoraRtcEngineKit, tokenPrivilegeWillExpire token: String) {
        getEventHander { delegate in
            delegate.onTokenPrivilegeWillExpire()
        }
    }
    
    func rtcEngine(_ engine: AgoraRtcEngineKit, receiveStreamMessageFromUid uid: UInt, streamId: Int, data: Data) {
        do {
            let content: NSDictionary = try JSONSerialization.jsonObject(with: data, options: .mutableContainers) as! NSDictionary
            let cmd: String? = content["cmd"] as? String
            guard let cmd = cmd else {
                return
            }
            if socketInfoItems?.data?.typeid == 492 {
                if self.singerRole == .audience {
                    lrcControl?.receiveStreamMessage?(content: content)
                }
            }
            switch cmd {
                case "musicStopped":
                    Logger.log(self, message: "musicStopped", level: .info)
                case "setLrcTime":
                if self.singerRole == .audience {
                    self.handleSetLrcTimeCommand(dict: content as! [String : Any], role: self.singerRole)
                }                
                default: break
            }
#if DEBUG
#warning("测试需要，确认是否点点需要该功能")
            didKTVAPIReceiveStreamMessageFrom(uid: NSInteger(uid), streamId: streamId, dict: content as! [String : Any])
#endif
        } catch {
            Logger.log(self, message: error.localizedDescription, level: .error)
        }
        
    }
    
    /// 观众在 audioMetadataReceived 回调内拿到歌词进度信息直接传给歌词组件
    func rtcEngine(_ engine: AgoraRtcEngineKit, audioMetadataReceived uid: UInt, metadata: Data) {
        guard let time: LrcTime = try? LrcTime(serializedData: metadata) else {return}
         if time.type == .lrcTime && self.singerRole == .audience {
             self.setProgress(with: Int(time.ts))
        }
    }
}

//需要外部转发的方法 主要是dataStream相关的
extension KTVApiImpl {
    public func updateVoicePitch(_ pitch: Double) {
        if singerRole != .leadSinger {
            self.pitch = pitch
        }
    }
    
    @objc public func didAudioRouteChanged(routing: AgoraAudioOutputRouting) {
        self.audioRoute = routing.rawValue
    }
    
    @objc public func didJoinedOfUid(uid: UInt, elapsed: Int) {
        if (singerRole != .audience && uid == apiConfig?.chorusUid ?? 0) {
            apiConfig?.engine?.muteRemoteAudioStream(UInt(apiConfig?.chorusUid ?? 0), mute: true)
        }
    }
    
    @objc public func didJoinChannel( channel: String, withUid uid: UInt, elapsed: Int) {
        if apiConfig?.isRoomOwner == true {
             
        }
    }
    
    @objc public func didLeaveChannelWith( stats: AgoraChannelStats) {
        if apiConfig?.isRoomOwner == true {
            stopSyncCloudConvergenceStatus()
        }
    }
    
    @objc public func didKTVAPIReceiveStreamMessageFrom(uid: NSInteger, streamId: NSInteger, dict: [String: Any]) {
        
        let role = singerRole
        guard let cmd = dict["cmd"] as? String else { return }
        
        switch cmd {
        case "setLrcTime":
            if forward == 1 && role == .audience {
                ///观众走子频道回调的进度
            } else {
                handleSetLrcTimeCommand(dict: dict, role: role)
            }
        case "PlayerState":
            handlePlayerStateCommand(dict: dict, role: role)
        case "setVoicePitch":
            handleSetVoicePitchCommand(dict: dict, role: role)
        default:
            break
        }
    }
    
    private func handleSetLrcTimeCommand(dict: [String: Any], role: KTVSingRole) {
        guard let position = dict["time"] as? Int64,
                let duration = dict["duration"] as? Int64,
                let realPosition = dict["realTime"] as? Int64,
               // let songCode = dict["songCode"] as? Int64,
                let mainSingerState = dict["playerState"] as? Int,
                let ntpTime = dict["ntp"] as? Int,
                let songId = dict["songIdentifier"] as? String
        else { return }
        agoraPrint("realTime:\(realPosition) position:\(position) lastNtpTime:\(lastNtpTime) ntpTime:\(ntpTime) ntpGap:\(ntpTime - self.lastNtpTime) ")
        //如果接收到的歌曲和自己本地的歌曲不一致就不更新进度
//        guard songCode == self.songCode else {
//            agoraPrint("local songCode[\(songCode)] is not equal to recv songCode[\(self.songCode)] role: \(singerRole.rawValue)")
//            return
//        }

        self.lastNtpTime = ntpTime
        self.remotePlayerDuration = TimeInterval(duration)
        
        let state = AgoraMediaPlayerState(rawValue: mainSingerState) ?? .stopped
//        self.lastMainSingerUpdateTime = Date().milListamp
//        self.remotePlayerPosition = TimeInterval(realPosition)
        if self.playerState != state {
            agoraPrint("[setLrcTime] recv state: \(self.playerState.rawValue)->\(state.rawValue) role: \(singerRole.rawValue) role: \(singerRole.rawValue)")
            
            if state == .playing, singerRole == .coSinger, playerState == .openCompleted {
                //如果是伴唱等待主唱开始播放，seek 到指定位置开始播放保证歌词显示位置准确
                self.localPlayerPosition = self.lastMainSingerUpdateTime - Double(position)
                agoraPrint("localPlayerPosition:playerKit:handleSetLrcTimeCommand \(localPlayerPosition)")
                agoraPrint("seek toPosition: \(position)")
                musicPlayer?.seek(toPosition: Int(position))
            }
            
            syncPlayStateFromRemote(state: state, needDisplay: false)
        }
        if role == .coSinger {
            self.lastMainSingerUpdateTime = Date().milListamp
            self.remotePlayerPosition = TimeInterval(realPosition)
            handleCoSingerRole(dict: dict)
        } else if role == .audience {
            // 需要根据发送的协议内是否有 ver 字段判断发送端是否新发送端, 如果有, 说明发送端是新发送端, 不做处理, 如果没有该字段, 说明发送端是老发送端, 需要完成 dataStreamMessage 的处理
            if dict.keys.contains("ver") {
                // 发送端是新发送端, 歌词信息需要从 audioMetadata 里取
                recvFromDataStream = false
            } else {
                // 发送端是老发送端, 歌词信息需要从 dataStreamMessage 里取
                recvFromDataStream = true
                self.lastMainSingerUpdateTime = Date().milListamp
                self.remotePlayerPosition = TimeInterval(realPosition)
                handleAudienceRole(dict: dict)
            }
        }
    }
    
    private func handlePlayerStateCommand(dict: [String: Any], role: KTVSingRole) {
        let mainSingerState: Int = dict["state"] as? Int ?? 0
        let state = AgoraMediaPlayerState(rawValue: mainSingerState) ?? .idle

        if state == .playing, singerRole == .coSinger, playerState == .openCompleted {
            //如果是伴唱等待主唱开始播放，seek 到指定位置开始播放保证歌词显示位置准确
            self.localPlayerPosition = getPlayerCurrentTime()
            agoraPrint("localPlayerPosition:playerKit:handlePlayerStateCommand \(localPlayerPosition)")
            agoraPrint("seek toPosition: \(self.localPlayerPosition)")
            musicPlayer?.seek(toPosition: Int(self.localPlayerPosition))
        }

        agoraPrint("recv state with MainSinger: \(state.rawValue)")
        syncPlayStateFromRemote(state: state, needDisplay: true)
    }

    private func handleSetVoicePitchCommand(dict: [String: Any], role: KTVSingRole) {
        if role == .audience, let voicePitch = dict["pitch"] as? Double {
            self.pitch = voicePitch
        }
    }

    private func handleCoSingerRole(dict: [String: Any]) {
        if musicPlayer?.getPlayerState() == .playing {
            let localNtpTime = getNtpTimeInMs()
            let localPosition = localNtpTime - Int(localPlayerSystemTime) + localPosition
            let expectPosition = Int(dict["time"] as? Int64 ?? 0) + localNtpTime - Int(dict["ntp"] as? Int64 ?? 0) + self.audioPlayoutDelay
            let threshold = expectPosition - Int(localPosition)
            let ntpTime = dict["ntp"] as? Int ?? 0
            let time = dict["time"] as? Int64 ?? 0
            agoraPrint("checkNtp, diff:\(threshold), localNtp:\(getNtpTimeInMs()), localPosition:\(localPosition), audioPlayoutDelay:\(audioPlayoutDelay), remoteDiff:\(String(describing: ntpTime - Int(time)))")
            let duration = dict["duration"] as? Int64 ?? 0
            
            if abs(threshold) > 80 && expectPosition <= duration {
                musicPlayer?.seek(toPosition: expectPosition)
                agoraPrint("CheckNtp, cosinger expectPosition: \(expectPosition) nowTime:\(Date().milListamp)")
                agoraPrint("progress: setthreshold: \(threshold) expectPosition: \(expectPosition), localNtpTime: \(localNtpTime), audioPlayoutDelay: \(self.audioPlayoutDelay), localPosition: \(localPosition)")
            }
        }
    }

    private func handleAudienceRole(dict: [String: Any]) {
        // do something for audience role
        guard let position = dict["time"] as? Int64,
                let duration = dict["duration"] as? Int64,
                let realPosition = dict["realTime"] as? Int64,
                let songCode = dict["songCode"] as? Int64,
                let mainSingerState = dict["playerState"] as? Int
        else { return }
        agoraPrint("audience: position: \(position) realPosition:\(realPosition)")
    }

    @objc public func didKTVAPIReceiveAudioVolumeIndication(with speakers: [AgoraRtcAudioVolumeInfo], totalVolume: NSInteger, songIdentifier: String) {
        if playerState != .playing {return}
        if singerRole == .audience {return}

        guard var pitch: Double = speakers.first?.voicePitch else {return}
        pitch = isNowMicMuted ? 0 : pitch
        //如果mpk不是playing状态 pitch = 0
        if musicPlayer?.getPlayerState() != .playing {pitch = 0}
        self.pitch = pitch
        //将主唱的pitch同步到观众
        if isMainSinger() {
            let dict: [String: Any] = [ "cmd": "setVoicePitch",
                                        "pitch": pitch,
                                        "songIdentifier": songIdentifier,
                                        "forward": true,
            ]
            sendStreamMessageWithDict(dict, success: nil)
        }
    }

    @objc public func didKTVAPILocalAudioStats(stats: AgoraRtcLocalAudioStats) {
        if useCustomAudioSource == true {return}
        audioPlayoutDelay = Int(stats.audioPlayoutDelay)
    }
}

//private method
extension KTVApiImpl {

    private func initTimer() {
        
        guard timer == nil else { return }

        timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true, block: {[weak self] timer in
            guard let self = self else {
                timer.invalidate()
                return
            }
            
            var current = self.getPlayerCurrentTime()
            if self.singerRole == .audience && (Date().milListamp - (self.lastMainSingerUpdateTime )) > 10000000 {
                return
            }
            
            if self.singerRole != .audience && (Date().milListamp - (self.lastReceivedPosition )) > 10000000 {
                return
            }

            if self.oldPitch == self.pitch && (self.oldPitch != 0 && self.pitch != 0) {
                self.pitch = -1
            }
            
            if self.singerRole != .audience {
                current = Date().milListamp - self.lastReceivedPosition + Double(self.localPosition)
            }
            
            if self.singerRole == .audience && !self.recvFromDataStream {
                // 歌词强同步方案下观众不使用这个定时器设置歌词进度
            } else {
                var curTime:Int64 = Int64(current) + Int64(self.startHighTime)
                if self.singerRole != .audience {
                    current = Date().milListamp - self.lastReceivedPosition + Double(self.localPosition)
                    
                    if self.singerRole == .leadSinger || self.singerRole == .soloSinger {
                        var time: LrcTime = LrcTime()
                        time.forward = true
                        time.ts = curTime
                        time.songID = self.songIdentifier
                        time.type = .lrcTime
                        //大合唱的uid是musicuid
                        time.uid = Int32(self.apiConfig?.localUid ?? 0)
                        self.sendMetaMsg(with: time)
                    }
                }
                self.setProgress(with: Int(curTime))
            }
            
            self.oldPitch = self.pitch
        })
    }

    private func setPlayerState(with state: AgoraMediaPlayerState) {
        playerState = state
        updateRemotePlayBackVolumeIfNeed()
        updateTimer(with: state)
    }

    private func updateRemotePlayBackVolumeIfNeed() {
        let role = singerRole
        if role == .audience {
            apiConfig?.engine?.adjustPlaybackSignalVolume(100)
            return
        }

        let vol = self.playerState == .playing ? remoteVolume : 100
        apiConfig?.engine?.adjustPlaybackSignalVolume(Int(vol))
    }

    private func updateTimer(with state: AgoraMediaPlayerState) {
        DispatchQueue.main.async {
            if state == .paused || state == .stopped {
                self.pauseTimer()
            } else if state == .playing {
                self.startTimer()
            }
        }
    }

    //timer method
    private func startTimer() {
        guard let timer = self.timer else {return}
        if isPause == false {
            RunLoop.current.add(timer, forMode: .common)
            self.timer?.fire()
        } else {
            resumeTimer()
        }
    }

    private func resumeTimer() {
        if isPause == false {return}
        isPause = false
        timer?.fireDate = Date()
    }

    private func pauseTimer() {
        if isPause == true {return}
        isPause = true
        timer?.fireDate = Date.distantFuture
    }

    private func freeTimer() {
        guard let _ = self.timer else {return}
        self.timer?.invalidate()
        self.timer = nil
    }

    private func getPlayerCurrentTime() -> TimeInterval {
        let role = singerRole
        if role == .soloSinger || role == .leadSinger{
            let time = Date().milListamp - localPlayerPosition
            return time
        } else if role == .coSinger {
            if playerState == .playing || playerState == .paused {
                let time = Date().milListamp - localPlayerPosition
                return time
            }
        }
        
        var position = Date().milListamp - self.lastMainSingerUpdateTime + remotePlayerPosition
        if playerState != .playing {
            position = remotePlayerPosition
        }
        return position
    }

    private func syncPlayStateFromRemote(state: AgoraMediaPlayerState, needDisplay: Bool) {
        let role = singerRole
        if role == .coSinger {
            if state == .stopped {
                stopSing()
            } else if state == .paused {
                pausePlay()
            } else if state == .playing {
                resumeSing()
            } else if (state == .playBackAllLoopsCompleted && needDisplay == true) {
                getEventHander { delegate in
                    delegate.onMusicPlayerStateChanged(state: state, error: .none, isLocal: true)
                }
            }
        } else {
            self.playerState = state
            getEventHander { delegate in
                delegate.onMusicPlayerStateChanged(state: self.playerState, error: .none, isLocal: false)
            }
        }
    }

    private func pausePlay() {
        musicPlayer?.pause()
    }
    
    private func dataToDictionary(data: Data) -> [String: Any]? {
        do {
            let json = try JSONSerialization.jsonObject(with: data, options: [])
            return json as? [String: Any]
        } catch {
            agoraPrint("Error decoding data: (error.localizedDescription)")
            return nil
        }
    }

    private func compactDictionaryToData(_ dict: [String: Any]) -> Data? {
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: dict, options: [])
            return jsonData
        } catch {
            agoraPrint("Error encoding data: (error.localizedDescription)")
            return nil
        }
    }

    private func getNtpTimeInMs() -> Int {
        var localNtpTime: Int = Int(apiConfig?.engine?.getNtpWallTimeInMs() ?? 0)

        if localNtpTime != 0 {
            localNtpTime = localNtpTime + 2208988800 * 1000
        }

        return localNtpTime
    }

    private func syncPlayState(state: AgoraMediaPlayerState, error: AgoraMediaPlayerError) {
        let dict: [String: Any] = ["cmd": "PlayerState", "userId": apiConfig?.localUid as Any, "state": state.rawValue, "error": "\(error.rawValue)"]
        sendStreamMessageWithDict(dict, success: nil)
    }

    private func sendStreamMessageWithDict(_ dict: [String: Any], success: ((_ success: Bool) -> Void)?) {
        let messageData = compactDictionaryToData(dict as [String: Any])
        let code = apiConfig?.engine?.sendStreamMessage(dataStreamId, data: messageData ?? Data())
        if code == 0 && success != nil { success!(true) }
        if code != 0 {
            agoraPrint("sendStreamMessage fail: \(String(describing: code))")
        }
    }

    private func syncPlayState(_ state: AgoraMediaPlayerState) {
        let dict: [String: Any] = [ "cmd": "PlayerState", "userId": apiConfig?.localUid as Any, "state": "\(state.rawValue)" ]
        sendStreamMessageWithDict(dict, success: nil)
    }
    
    private func setProgress(with pos: Int) {
        mLastSetPlayPosTime = TimeInterval(pos)
        lrcControl?.onUpdatePitch(pitch: Float(self.pitch))
        if self.singerRole != .audience {
            // 我们在拿到进度后，需要把歌词的进度减少200ms，来保证歌词歌曲进度同步
            lrcControl?.onUpdateProgress(progress: pos > 200 ? pos - 200 : pos)
        } else {
            // 我们在拿到进度后，需要把歌词的进度减少300ms，来保证歌词歌曲进度同步
            lrcControl?.onUpdateProgress(progress: pos > 300 ? pos - 300 : pos)
        }
    }
    
    private func sendMetaMsg(with time: LrcTime) {
        let data: Data? = try? time.serializedData()
        guard let mpkConnection = mpkConnection else { return }
        let code = apiConfig?.engine?.sendAudioMetadataEx(mpkConnection, metadata: data ?? Data())
        if code != 0 {
            agoraPrint("sendStreamMessage fail: \(String(describing: code))")
        }
    }
}

//主要是MPK的回调
extension KTVApiImpl: AgoraRtcMediaPlayerDelegate {
    
    func AgoraRtcMediaPlayer(_ playerKit: AgoraRtcMediaPlayerProtocol, didChangedTo position_ms: Int, atTimestamp timestamp_ms: TimeInterval) {
        self.lastReceivedPosition = Date().milListamp
        self.localPosition = Int(position_ms)
        self.localPlayerSystemTime = timestamp_ms
        self.localPlayerPosition = Date().milListamp - Double(position_ms)
        if isMainSinger() && getPlayerCurrentTime() > TimeInterval(self.audioPlayoutDelay) {
            let dict: [String: Any] = [ "cmd": "setLrcTime",
                                        "duration": self.playerDuration,
                                        "time": position_ms - audioPlayoutDelay,
                                        //不同机型delay不同，需要发送同步的时候减去发送机型的delay，在接收同步加上接收机型的delay
                                        "realTime":position_ms,
                                        "ntp": timestamp_ms,
                                        "playerState": self.playerState.rawValue,
                                        "songIdentifier": songIdentifier,
                                        "forward": forward == 1 ? true : false,
                                        "songCode": self.songCode,
                                        // 新版本的发送端需要在发送 AudioMetaData 的同时照常发送 dataStream, 并在协议上多加一个字段协议新增的字段: msg["ver"] = 2
                                        "ver":2
            ]
            agoraPrint("position_ms:\(position_ms), ntp:\(getNtpTimeInMs()), delta:\(self.getNtpTimeInMs() - position_ms), autoPlayoutDelay:\(self.audioPlayoutDelay)")
            sendStreamMessageWithDict(dict, success: nil)
            
            // 大合唱场景使用
            if (singerRole == .leadSinger && apiConfig?.ktvType == .Cantata) {
                sendStreamMessageWithJsonObjectEx(dict) { _ in
                    
                }
            }
        }
    }

    func AgoraRtcMediaPlayer(_ playerKit: AgoraRtcMediaPlayerProtocol, didChangedTo position: Int) {
        
    }
    
    func AgoraRtcMediaPlayer(_ playerKit: AgoraRtcMediaPlayerProtocol, didChangedTo state: AgoraMediaPlayerState, error: AgoraMediaPlayerError) {
        agoraPrint("agoraRtcMediaPlayer didChangedToState: \(state.rawValue) \(self.songCode)")
        if isRelease {return}
        if state == .openCompleted {
            self.localPlayerPosition = Date().milListamp
            agoraPrint("localPlayerPosition:playerKit:openCompleted \(localPlayerPosition)")
            self.playerDuration = TimeInterval(musicPlayer?.getDuration() ?? 0)
            playerKit.selectAudioTrack(1)
            if isMainSinger() { //主唱播放，通过同步消息“setLrcTime”通知伴唱play
                playerKit.play()
            }
        } else if state == .stopped {
            self.localPlayerPosition = Date().milListamp
            self.playerDuration = 0
        }
        else if state == .paused {
        } else if state == .playing {
            self.localPlayerPosition = Date().milListamp - Double(musicPlayer?.getPosition() ?? 0)
            agoraPrint("localPlayerPosition:playerKit:playing \(localPlayerPosition)")
        }

        if isMainSinger() {
            syncPlayState(state: state, error: error)
        }
        self.playerState = state
        agoraPrint("recv state with player callback : \(state.rawValue)")
        if state == .playBackAllLoopsCompleted && singerRole == .coSinger {//可能存在伴唱不返回allloopbackComplete状态 这个状态通过主唱的playerState来同步
            return
        }
        getEventHander { delegate in
            delegate.onMusicPlayerStateChanged(state: state, error: .none, isLocal: true)
        }
    }

    private func isMainSinger() -> Bool {
        return singerRole == .soloSinger || singerRole == .leadSinger
    }
}

//MARK: AgoraMusicContentCenterExEventDelegate
extension KTVApiImpl: AgoraMusicContentCenterExEventDelegate {
    func onInitializeResult(_ state: AgoraMusicContentCenterExState, reason: AgoraMusicContentCenterExStateReason) {
        agoraPrint("onInitializeResult state: \(state.rawValue) reason: \(reason.rawValue)")

    }
    
    func onStartScoreResult(_ songCode: Int, state: AgoraMusicContentCenterExState, reason: AgoraMusicContentCenterExStateReason) {
        agoraPrint("onStartScoreResult[\(songCode)] state: \(state.rawValue) reason: \(reason.rawValue)")
        DispatchQueue.main.async {
            let _songCode = "\(songCode)"
            guard let block = self.scoreCallbacks[_songCode] else { return }
            self.scoreCallbacks.removeValue(forKey: _songCode)
            block(state, songCode)
        }
    }
    
    func onPreLoadEvent(_ requestId: String, songCode: Int, percent: Int, lyricPath: String?, pitchPath: String?, offsetBegin: Int, offsetEnd: Int, state: AgoraMusicContentCenterExState, reason: AgoraMusicContentCenterExStateReason) {
        agoraPrint("onPreLoadEvent[\(songCode)] state: \(state.rawValue) reason: \(reason.rawValue)")
        DispatchQueue.main.async {
            if let listener = self.loadMusicListeners.object(forKey: "\(songCode)" as NSString) as? IMusicLoadStateListener {
                listener.onMusicLoadProgress(songCode: songCode, percent: percent, status: state, msg: String(reason.rawValue), lyricUrl: lyricPath ?? "")
            }
            if (state == .preloading) { return }
            TWLog("songCode:\(songCode), status:\(reason.rawValue), code:\(reason.rawValue)")
            let SongCode = "\(songCode)"
            guard let block = self.musicCallbacks[SongCode] else { return }
            self.musicCallbacks.removeValue(forKey: SongCode)
            //        if (errorCode == .errorGateway) {
            //            getEventHander { delegate in
            //                delegate.onTokenPrivilegeWillExpire()
            //            }
            //        }
            block(state, songCode)
        }
    }
    
    func onLyricResult(_ requestId: String, songCode: Int, lyricPath: String?, offsetBegin: Int, offsetEnd: Int, reason: AgoraMusicContentCenterExStateReason) {
        agoraPrint("onLyricResult[\(songCode)] reason: \(reason.rawValue)")
        DispatchQueue.main.async {
            guard let lrcUrl = lyricPath else {return}
            let callback = self.lyricCallbacks[requestId]
            guard let lyricCallback = callback else { return }
            self.lyricCallbacks.removeValue(forKey: requestId)
            //        if (reason == .errorGateway) {
            //            getEventHander { delegate in
            //                delegate.onTokenPrivilegeWillExpire()
            //            }
            //        }
            if lrcUrl.isEmpty {
                lyricCallback(nil)
                TWLog("onLyricResult: lrcUrl.isEmpty")
                return
            }
            lyricCallback(lrcUrl)
            TWLog("onLyricResult: lrcUrl is \(lrcUrl)")
        }
    }
    
    func onPitchResult(_ requestId: String, songCode: Int, pitchPath: String?, offsetBegin: Int, offsetEnd: Int, reason: AgoraMusicContentCenterExStateReason) {
        agoraPrint("onPitchResult[\(songCode)] reason: \(reason.rawValue)")
        DispatchQueue.main.async {
            guard let pitchPath = pitchPath else {return}
            let callback = self.pitchCallbacks[requestId]
            guard let pitchCallback = callback else { return }
            self.pitchCallbacks.removeValue(forKey: requestId)
            //        if (reason == .errorGateway) {
            //            getEventHander { delegate in
            //                delegate.onTokenPrivilegeWillExpire()
            //            }
            //        }
            if pitchPath.isEmpty {
                pitchCallback(nil)
                TWLog("onPitchResult: pitchPath.isEmpty")
                return
            }
            pitchCallback(pitchPath)
            TWLog("onPitchResult: pitchPath is \(pitchPath)")
        }
    }
}

//MARK: AgoraMusicContentCenterExScoreEventDelegate
extension KTVApiImpl: AgoraMusicContentCenterExScoreEventDelegate {
    func onPitch(_ songCode: Int, data: AgoraRawScoreData) {
        lrcControl?.onPitch(songCode: songCode, data: data)
    }
    
    func onLineScore(_ songCode: Int, value: AgoraLineScoreData) {
        lrcControl?.onLineScore(songCode: songCode, value: value)
    }
}

extension Date {
    /// 获取当前 秒级 时间戳 - 10位
    ///
//    var timeStamp : TimeInterval {
//        let timeInterval: TimeInterval = self.timeIntervalSince1970
//        return timeInterval
//    }
    /// 获取当前 毫秒级 时间戳 - 13位
    var milListamp : TimeInterval {
        let timeInterval: TimeInterval = self.timeIntervalSince1970
        let millisecond = CLongLong(round(timeInterval*1000))
        return TimeInterval(millisecond)
    }
}

