//
//  KTVViewController.swift
//  KTVApiDemo
//
//  Created by CP on 2023/8/8.
//

import UIKit
import AgoraRtcKit
public enum LoadMusicType: Int {
    case mcc //声网歌曲中心
    case local //本地音乐
}

class KTVViewController: UIViewController {

    var role: KTVSingRole = .audience
    var channelName: String = ""
    var type: LoadMusicType = .mcc
    var rtcKit: AgoraRtcEngineKit!
    var rtcDataStreamId: Int = 0
    var ktvApi: KTVApiImpl!
    var rtcToken: String?
    var rtmToken: String?
    var rtcPlayerToken: String?
    var userId: Int = 0
    
    let mainSingerId = 1000
    let coSingerId = 2000
    let audienceId = 3000
    
    let mccSongCode = 6654550232746660
    
    var lyricView: KTVLyricView!
    
    override func viewDidLoad() {
        super.viewDidLoad()

        self.view.backgroundColor = .white
        self.title = "KTV online"
        
        if role == .leadSinger {
            userId = mainSingerId
        } else if role == .coSinger {
            userId = coSingerId
        } else {
            userId = audienceId
        }
        
        /*
         1.加载RTC
         2.初始化KTV API
         3.切换角色
         4.加载歌曲或者歌词
         */
        
        layoutUI()
        joinRTCChannel()
        loadKTVApi()
        
        
    }
    
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        leaveChannel()
    }
    
    private func layoutUI() {
        lyricView = KTVLyricView(frame: CGRect(x: 50, y: 100, width: UIScreen.main.bounds.size.width - 100, height: 400))
        view.addSubview(lyricView)
    }
    
    private func joinRTCChannel() {
        rtcKit = AgoraRtcEngineKit.sharedEngine(withAppId: KeyCenter.AppId, delegate: self)
        rtcKit.setAudioProfile(.musicHighQuality)
        rtcKit.setAudioScenario(.gameStreaming)
        rtcKit.setChannelProfile(.liveBroadcasting)
        rtcKit.enableAudioVolumeIndication(50, smooth: 10, reportVad: true)
        rtcKit.enableAudio()
        rtcKit.setEnableSpeakerphone(true)
        
        let config = AgoraDataStreamConfig.init()
        config.ordered = false
        config.syncWithAudio = false
        rtcKit.createDataStream(&rtcDataStreamId, config: config)
        rtcKit.setClientRole(role == .audience ? .audience : .broadcaster)

    }
    
    private func loadKTVApi() {
        getMccData(with: "\(userId)") {[weak self] rtcToken, rtmToken, rtcPlayerToken in
            guard let self = self else {return}
            self.rtcToken = rtcToken
            self.rtmToken = rtmToken
            self.rtcPlayerToken = rtcPlayerToken
            
            let apiConfig = KTVApiConfig(appId: KeyCenter.AppId, rtmToken: self.type == .mcc ? (self.rtmToken ?? "") : "", engine: self.rtcKit, channelName: self.channelName, localUid: self.userId, chorusChannelName: "\(self.channelName)_ex", chorusChannelToken: self.rtcPlayerToken ?? "", type: .normal, maxCacheSize: 10, musicType: self.type == .mcc ? .mcc : .local, isDebugMode: false)
            self.ktvApi = KTVApiImpl(config: apiConfig)
            self.ktvApi.renewInnerDataStreamId()
            self.ktvApi.setLrcView(view: self.lyricView)
            self.ktvApi.addEventHandler(ktvApiEventHandler: self)
            
            self.rtcKit.joinChannel(byToken: KeyCenter.Token, channelId: self.channelName, uid: UInt(self.userId), mediaOptions: self.mediaOptions())
            self.loadMusic()
            self.switchRole()
        }
    }
    
    private func mediaOptions() -> AgoraRtcChannelMediaOptions {
        let options = AgoraRtcChannelMediaOptions()
        options.clientRoleType = role != .audience ? .broadcaster : .audience
        options.publishMicrophoneTrack = role != .audience ? true : false
        options.publishCustomAudioTrack = false
        options.channelProfile = .liveBroadcasting
        options.autoSubscribeAudio = true
        if type == .mcc {
            options.publishMediaPlayerId = Int(ktvApi.getMusicPlayer()?.getMediaPlayerId() ?? 0)
        }
        options.enableAudioRecordingOrPlayout = true
        return options
    }
    
    
    
    private func switchRole() {
        ktvApi.switchSingerRole(newRole: role) { state, failReason in
            
        }
    }
    
    private func loadMusic() {
        if type == .local {
            let mUrl = Bundle.main.path(forResource: "成都", ofType: "mp3")!
            let lUrl = Bundle.main.path(forResource: "成都", ofType: "xml")!
            let songConfig = KTVSongConfiguration()
            songConfig.autoPlay = (role == .leadSinger || role == .soloSinger) ? true : false
            songConfig.mode = role == .audience ? .loadNone : .loadMusicOnly
            songConfig.mainSingerUid = mainSingerId
            songConfig.songIdentifier = "chengdu"
            ktvApi.loadMusic(config: songConfig, url: mUrl)
            
            self.lyricView.resetLrcData(with: lUrl)
        } else {
            let songConfig = KTVSongConfiguration()
            songConfig.autoPlay = (role == .leadSinger || role == .soloSinger) ? true : false
            songConfig.mode = role == .audience ? .loadLrcOnly : .loadMusicAndLrc
            songConfig.mainSingerUid = mainSingerId
            songConfig.songIdentifier = "\(mccSongCode)"
            ktvApi.loadMusic(songCode: mccSongCode, config: songConfig, onMusicLoadStateListener: self)
        }
    }
    
    private func leaveChannel() {
        ktvApi.cleanCache()
        rtcKit.leaveChannel()
    }
    
    private func getMccData(with userId: String, completion:@escaping ((String, String, String)->Void)) {
        var tokenMap1:[Int: String] = [:], tokenMap2:[Int: String] = [:]
        
        let dispatchGroup = DispatchGroup()
        dispatchGroup.enter()
        NetworkManager.shared.generateTokens(channelName: channelName,
                                             uid: userId,
                                             tokenGeneratorType: .token006,
                                             tokenTypes: [.rtc, .rtm]) { tokenMap in
            tokenMap1 = tokenMap
            dispatchGroup.leave()
        }
        
        dispatchGroup.enter()
        NetworkManager.shared.generateTokens(channelName: "\(channelName)_ex",
                                             uid: userId,
                                             tokenGeneratorType: .token006,
                                             tokenTypes: [.rtc]) { tokenMap in
            tokenMap2 = tokenMap
            dispatchGroup.leave()
        }
        
        dispatchGroup.notify(queue: .main){
           if let rtcToken = tokenMap1[NetworkManager.AgoraTokenType.rtc.rawValue],
            let rtmToken = tokenMap1[NetworkManager.AgoraTokenType.rtm.rawValue],
              let rtcPlayerToken = tokenMap2[NetworkManager.AgoraTokenType.rtc.rawValue] {
               completion(rtcToken, rtmToken, rtcPlayerToken)
           } else {
               print("获取MCC信息失败")
           }
        }
    }
    
}

extension KTVViewController: AgoraRtcEngineDelegate {
    func rtcEngine(_ engine: AgoraRtcEngineKit, reportAudioVolumeIndicationOfSpeakers speakers: [AgoraRtcAudioVolumeInfo], totalVolume: Int) {
        if let ktvApi = self.ktvApi {
            ktvApi.didKTVAPIReceiveAudioVolumeIndication(with: speakers, totalVolume: totalVolume)
        }
    }
    
    func rtcEngine(_ engine: AgoraRtcEngineKit, receiveStreamMessageFromUid uid: UInt, streamId: Int, data: Data) {
        if let ktvApi = self.ktvApi {
            ktvApi.didKTVAPIReceiveStreamMessageFrom(uid: NSInteger(uid), streamId: streamId, data: data)
        }
    }
}

extension KTVViewController: IMusicLoadStateListener {
    func onMusicLoadProgress(songCode: Int, percent: Int, status: AgoraMusicContentCenterPreloadStatus, msg: String?, lyricUrl: String?) {
        //歌曲加载进度
        print("歌曲加载进度:\(percent)%")
    }
    
    func onMusicLoadSuccess(songCode: Int, lyricUrl: String) {

    }
    
    func onMusicLoadFail(songCode: Int, reason: KTVLoadSongFailReason) {
        //歌曲加载失败
    }
}

extension KTVViewController: KTVApiEventHandlerDelegate {
    func onMusicPlayerStateChanged(state: AgoraMediaPlayerState, error: AgoraMediaPlayerError, isLocal: Bool) {
        
    }
    
    func onSingingScoreResult(score: Float) {
        
    }
    
    func onSingerRoleChanged(oldRole: KTVSingRole, newRole: KTVSingRole) {
        
    }
    
    func onTokenPrivilegeWillExpire() {
        
    }
    
    func onChorusChannelAudioVolumeIndication(speakers: [AgoraRtcAudioVolumeInfo], totalVolume: Int) {
        
    }

}
