//
//  KTVLyricView.swift
//  KTVApiDemo
//
//  Created by CP on 2023/8/8.
//

import UIKit
import AgoraLyricsScore
class KTVLyricView: UIView {
    var downloadManager = LyricsFileDownloader()
    var lrcView: KaraokeView!
    override init(frame: CGRect) {
        super.init(frame: frame)
        layoutUI()
        downloadManager.delegate = self
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func layoutUI() {
        lrcView = KaraokeView(frame: self.bounds, loggers: [FileLogger()])
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

extension KTVLyricView: KTVLrcViewDelegate, KaraokeDelegate {
    func onUpdatePitch(pitch: Float) {
        lrcView.setPitch(pitch: Double(pitch))
    }
    
    func onUpdateProgress(progress: Int) {
        lrcView.setProgress(progress: progress)
    }
    
    func onDownloadLrcData(url: String) {
        //开始歌词下载
        let _ = downloadManager.download(urlString: url)
    }
    
    func resetLrcData(with url: String) {
        let musicUrl = URL(fileURLWithPath: url)
        guard let data = try? Data(contentsOf: musicUrl),
              let model = KaraokeView.parseLyricData(data: data) else {
            return
        }
        lrcView?.setLyricData(data: model)
    }
    
    func onHighPartTime(highStartTime: Int, highEndTime: Int) {
        
    }
}

extension KTVLyricView: LyricsFileDownloaderDelegate {
    func onLyricsFileDownloadProgress(requestId: Int, progress: Float) {
        
    }
    
    func onLyricsFileDownloadCompleted(requestId: Int, fileData: Data?, error: DownloadError?) {
        guard let data = fileData, let model = KaraokeView.parseLyricData(data: data) else {
            return
        }
        lrcView?.setLyricData(data: model)
    }
}
