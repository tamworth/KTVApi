//
//  ViewController.swift
//  KTVApiDemo
//
//  Created by CP on 2023/8/8.
//

import UIKit

class ViewController: UIViewController {
    var role: KTVSingRole = .audience
    var channelName: String = ""
    var type: LoadMusicType = .mcc
    var rtcToken: String?
    var rtmToken: String?
    var rtcPlayerToken: String?
    var userId: Int = 0
    var selBtn: UIButton?
    var isCantata: Bool = false
    @IBOutlet weak var tf: UITextField!
    override func viewDidLoad() {
        super.viewDidLoad()

    }

    
    @IBAction func leadSet(_ sender: UIButton) {
        role = .leadSinger
        if self.selBtn != nil {
            self.selBtn?.setTitleColor(.white, for: .normal)
        }
        sender.setTitleColor(.red, for: .normal)
        self.selBtn = sender
    }
 
    @IBAction func auSet(_ sender: UIButton) {
        role = .audience
        if self.selBtn != nil {
            self.selBtn?.setTitleColor(.white, for: .normal)
        }
        sender.setTitleColor(.red, for: .normal)
        self.selBtn = sender
    }
    
    @IBAction func valueChange(_ sender: UISegmentedControl) {
        type = sender.selectedSegmentIndex == 0 ? .mcc : .local
    }
    
    @IBAction func ktvTypeChange(_ sender: UISegmentedControl) {
        isCantata = sender.selectedSegmentIndex == 0 ? false : true
    }
    
    @IBAction func startSing(_ sender: UIButton) {
        if tf.text?.count == 0 {
            return
        }
        
        channelName = tf.text!
        let vc = KTVViewController()
        vc.role = role
        vc.type = type
        vc.isCantata = isCantata
        vc.channelName = channelName
        self.navigationController?.pushViewController(vc, animated: true)
    }
    
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        tf.resignFirstResponder()
    }
    
}

