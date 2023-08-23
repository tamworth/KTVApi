//
//  KTVLyricView.swift
//  KTVApiDemo
//
//  Created by CP on 2023/8/8.
//

import UIKit
import AgoraLyricsScore
class KTVLyricView: UIView {
    var downloadManager = AgoraDownLoadManager()
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
        lrcView.lyricsView.textNormalColor = UIColor(red: 1, green: 1, blue: 1, alpha: 0.5)
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
        startDownloadLrc(with: url) {[weak self] url in
            guard let self = self, let url = url else {return}
            self.resetLrcData(with: url)
        }
    }
    
    func startDownloadLrc(with url: String, callBack: @escaping LyricCallback) {
        var path: String? = nil
        downloadManager.downloadLrcFile(urlString: url) { lrcurl in
            defer {
                callBack(path)
            }
            guard let lrcurl = lrcurl else {
                print("downloadLrcFile fail, lrcurl is nil")
                return
            }

            let curSong = URL(string: url)?.lastPathComponent.components(separatedBy: ".").first
            let loadSong = URL(string: lrcurl)?.lastPathComponent.components(separatedBy: ".").first
            guard curSong == loadSong else {
                print("downloadLrcFile fail, missmatch, cur:\(curSong ?? "") load:\(loadSong ?? "")")
                return
            }
            path = lrcurl
        } failure: {
            callBack(nil)
            print("歌词解析失败")
        }
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

extension KTVLyricView: AgoraLrcDownloadDelegate {
    public func downloadLrcFinished(url: String) {
        print("download lrc finished \(url)")
    }
    
    public func downloadLrcError(url: String, error: Error?) {
        print("download lrc fail \(url): \(String(describing: error))")
    }
}
