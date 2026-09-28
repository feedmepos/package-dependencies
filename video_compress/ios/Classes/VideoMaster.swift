import AVFoundation
import Flutter
import UIKit

/// The video master (FeedVibe `media §6.3`): a probe, a pass-through trim or a single 1080p
/// transcode, and the first frame at full resolution. Which of the two to write is decided by the
/// caller from `probe`; this only carries it out.
class VideoMaster {
    private var exporter: AVAssetExportSession?

    private func outURL(_ ext: String) -> URL {
        let dir = URL(fileURLWithPath: Utility.basePath()).appendingPathComponent("master")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(UUID().uuidString).\(ext)")
    }

    /// Display size (after rotation), duration, frame rate, bitrate, codec and file size.
    func probe(_ path: String) -> [String: Any]? {
        let url = Utility.getPathUrl(path)
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else { return nil }

        let size = track.naturalSize.applying(track.preferredTransform)
        var codec = "unknown"
        if let description = track.formatDescriptions.first {
            switch CMFormatDescriptionGetMediaSubType(description as! CMFormatDescription) {
            case kCMVideoCodecType_H264: codec = "h264"
            case kCMVideoCodecType_HEVC: codec = "hevc"
            default: codec = "other"
            }
        }
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0

        var hevcEncoder = false
        if #available(iOS 11.0, *) {
            hevcEncoder = AVAssetExportSession.exportPresets(compatibleWith: asset)
                .contains(AVAssetExportPresetHEVC1920x1080)
        }

        return [
            "width": Int(abs(size.width)),
            "height": Int(abs(size.height)),
            "durationMs": Int(asset.duration.seconds * 1000),
            "frameRate": Int(track.nominalFrameRate.rounded()),
            "bitrate": Int(track.estimatedDataRate),
            "codec": codec,
            "fileSize": fileSize,
            "hevcEncoder": hevcEncoder,
        ]
    }

    /// Writes the master. `transcode` false means pass-through: the container is rewritten and no
    /// frame is re-encoded. Otherwise one export at 1080p, HEVC when `hevc`. Location and other
    /// personal metadata are filtered out in both branches. A transcode of a source faster than
    /// `frameRate` is drawn at `frameRate`: the presets keep the source rate. Answers the output
    /// path, or nil.
    ///
    /// `ponytail: the export preset picks its own bitrate; move to AVAssetWriter with
    /// AVVideoAverageBitRateKey only if measured output is more than 50% over the media §6.3
    /// target.`
    func prepare(_ path: String, startMs: Double?, endMs: Double?, transcode: Bool, hevc: Bool,
                 frameRate: Int32, result: @escaping FlutterResult) {
        let asset = AVURLAsset(url: Utility.getPathUrl(path))

        var preset = AVAssetExportPresetPassthrough
        if transcode {
            preset = AVAssetExportPreset1920x1080
            if #available(iOS 11.0, *), hevc {
                preset = AVAssetExportPresetHEVC1920x1080
            }
        }
        guard let exporter = AVAssetExportSession(asset: asset, presetName: preset) else {
            return result(nil)
        }

        let out = outURL("mp4")
        exporter.outputURL = out
        exporter.outputFileType = .mp4
        exporter.shouldOptimizeForNetworkUse = true
        exporter.metadataItemFilter = AVMetadataItemFilter.forSharing()

        if transcode, #available(iOS 10.0, *),
           let track = asset.tracks(withMediaType: .video).first,
           track.nominalFrameRate > Float(frameRate) + 0.5 {
            let composition = AVMutableVideoComposition(propertiesOf: asset)
            composition.frameDuration = CMTime(value: 1, timescale: frameRate)
            // Without this the export keeps the source's frame timing and ignores the rate.
            composition.sourceTrackIDForFrameTiming = kCMPersistentTrackID_Invalid
            exporter.videoComposition = composition
        }

        if startMs != nil || endMs != nil {
            let timescale = asset.duration.timescale
            let start = CMTimeMakeWithSeconds((startMs ?? 0) / 1000, preferredTimescale: timescale)
            let end = endMs.map { CMTimeMakeWithSeconds($0 / 1000, preferredTimescale: timescale) }
                ?? asset.duration
            exporter.timeRange = CMTimeRange(start: start, end: end)
        }

        self.exporter = exporter
        exporter.exportAsynchronously {
            self.exporter = nil
            if exporter.status == .completed {
                result(out.path)
            } else {
                try? FileManager.default.removeItem(at: out)
                result(nil)
            }
        }
    }

    func cancel() {
        exporter?.cancelExport()
    }

    /// Frame 0 at full display resolution, as a lossless PNG; the caller does the one encode.
    func firstFrame(_ path: String) -> String? {
        let asset = AVURLAsset(url: Utility.getPathUrl(path))
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        generator.maximumSize = .zero

        guard let image = try? generator.copyCGImage(at: .zero, actualTime: nil),
              let png = UIImage(cgImage: image).pngData() else { return nil }
        let out = outURL("png")
        guard (try? png.write(to: out)) != nil else { return nil }
        return out.path
    }
}
