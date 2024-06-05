//
//  KTVLyricView.swift
//  KTVApiDemo
//
//  Created by CP on 2023/8/8.
//

import UIKit
import AgoraLyricsScoreEx
import AgoraMccExService

class KTVLyricView: UIView {
    var lrcView: KaraokeViewEx!
    override init(frame: CGRect) {
        super.init(frame: frame)
        layoutUI()
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func layoutUI() {
        lrcView = KaraokeViewEx(frame: self.bounds, loggers: [FileLoggerEx()])
        lrcView.scoringView.viewHeight = 60
        lrcView.scoringView.topSpaces = 5
        lrcView.backgroundColor = .lightGray
        lrcView.lyricsView.inactiveLineTextColor = UIColor(red: 1, green: 1, blue: 1, alpha: 0.5)
       // lrcView.lyricsView.textHighlightedColor = UIColor(hex: "#EEFF25")
        lrcView.lyricsView.lyricLineSpacing = 6
        lrcView.lyricsView.draggable = false
        lrcView.delegate = self
        addSubview(lrcView!)

    }
}

extension KTVLyricView: KTVLrcViewDelegate, KaraokeDelegateEx {
    
    
    func onUpdatePitch(pitch: Float) {
//        lrcView.setPitch(pitch: Double(pitch))
    }
    
    func onUpdateProgress(progress: Int) {
        lrcView.setProgress(progressInMs: UInt(progress))
    }
    
    func onPitch(songCode: Int, data: AgoraRawScoreData) {
        lrcView.setPitch(speakerPitch: data.speakerPitch, progressInMs: data.progressInMs)
    }
    
    func onLineScore(songCode: Int, value: AgoraLineScoreData) {
        
    }
    
    func onDownloadLrcData(lrcPath: String, pitchPath: String?) {
        //开始歌词下载
//        let _ = downloadManager.download(urlString: url)
        resetLrcData(lyricPath: lrcPath, pitchPath: pitchPath)
    }
    
    func resetLrcData(lyricPath: String, pitchPath: String?) {
        let lyricUrl = URL(fileURLWithPath: lyricPath)
        let pitchUrl = URL(fileURLWithPath: pitchPath ?? "")
        let pitchData = try? Data(contentsOf: pitchUrl)
        guard let data = try? Data(contentsOf: lyricUrl),
              let model = KaraokeViewEx.parseLyricData(krcFileData: data, pitchFileData: pitchData) else {
            return
        }
        lrcView?.setLyricData(data: model)
    }
    
    func onHighPartTime(highStartTime: Int, highEndTime: Int) {
        
    }
}

