//! The Playback screen: what the TV says it can play. Video decoders (from the media_codecs XML),
//! screen modes and HDR (dumpsys display), sound output and passthrough (settings, dumpsys audio),
//! DRM (the Widevine reader in tui/drminfo, run as the shell user) and Netflix. Pure: the worker reads.

use std::collections::BTreeMap;

/// The video formats the screen lists, in order: (label, MIME types that count).
pub const VIDEO: &[(&str, &[&str])] = &[
    ("H.264 (AVC)", &["video/avc"]),
    ("HEVC (H.265)", &["video/hevc"]),
    ("VP9", &["video/x-vnd.on2.vp9"]),
    ("AV1", &["video/av01"]),
    (
        "Dolby Vision",
        &["video/dolby-vision", "video/hevcdv", "video/avcdv"],
    ),
    ("MPEG-2", &["video/mpeg2"]),
];

/// How a format is decoded.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decode {
    Hardware,
    Software,
    NotListed,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Mode {
    pub id: u32,
    pub width: u32,
    pub height: u32,
    pub fps: f32,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Drm {
    /// Ran at all (TV Home installed and the helper answered).
    pub read: bool,
    pub widevine: bool,
    pub playready: bool,
    pub level: Option<String>,
    pub version: Option<String>,
    pub system_id: Option<String>,
    pub hdcp: Option<String>,
    pub max_hdcp: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Playback {
    /// Per VIDEO entry, in the same order.
    pub video: Vec<Decode>,
    pub modes: Vec<Mode>,
    pub active: Option<u32>,
    /// "Dolby Vision", "HDR10"…; empty when the TV doesn't report any.
    pub hdr: Vec<String>,
    pub audio_device: Option<String>,
    /// encoded_surround_output: None = never set (Auto).
    pub passthrough: Option<u8>,
    pub formats: Vec<String>,
    pub drm: Drm,
    /// Netflix's DRM plugin or its system properties are on the TV.
    pub netflix_partner: bool,
    pub netflix_version: Option<String>,
}

/// Decoders in `<Decoders>` blocks of one or more concatenated media_codecs XML files: MIME type →
/// codec names. Both `<MediaCodec name=… type=…>` and `<MediaCodec name=…><Type name=…/>` forms.
pub fn parse_decoders(xml: &str) -> BTreeMap<String, Vec<String>> {
    let mut out: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for block in xml.split("<Decoders>").skip(1) {
        let block = block.split("</Decoders>").next().unwrap_or("");
        for codec in block.split("<MediaCodec ").skip(1) {
            let head = codec.split('>').next().unwrap_or("");
            let Some(name) = attr(head, "name") else {
                continue;
            };
            let body = codec.split("</MediaCodec>").next().unwrap_or("");
            let software = attr(head, "software-codec").is_some_and(|v| v == "true")
                || body.contains("software-codec");
            let mut types: Vec<String> = attr(head, "type").into_iter().collect();
            if !head.ends_with('/') {
                for t in body.split("<Type ").skip(1) {
                    if let Some(n) = attr(t.split('>').next().unwrap_or(""), "name") {
                        types.push(n);
                    }
                }
            }
            let tagged = if software {
                format!("{name} [software]")
            } else {
                name
            };
            for t in types {
                out.entry(t.to_ascii_lowercase())
                    .or_default()
                    .push(tagged.clone());
            }
        }
    }
    out
}

/// `key="value"` inside a tag.
fn attr(tag: &str, key: &str) -> Option<String> {
    let at = tag.find(&format!("{key}=\""))? + key.len() + 2;
    let end = tag[at..].find('"')? + at;
    Some(tag[at..end].to_string())
}

/// Whether the decoders cover a format, and how.
pub fn decode_for(decoders: &BTreeMap<String, Vec<String>>, mimes: &[&str]) -> Decode {
    let names: Vec<&String> = mimes
        .iter()
        .filter_map(|m| decoders.get(*m))
        .flatten()
        .collect();
    if names.is_empty() {
        return Decode::NotListed;
    }
    let software = |n: &str| {
        let l = n.to_ascii_lowercase();
        l.starts_with("omx.google.") || l.starts_with("c2.android.") || l.ends_with("[software]")
    };
    if names.iter().any(|n| !software(n)) {
        Decode::Hardware
    } else {
        Decode::Software
    }
}

/// `dumpsys display`: the supported modes (`{id=1, width=1920, height=1080, fps=60.0}`), the active
/// one, and HDR types (`mSupportedHdrTypes=[1, 2]`).
pub fn parse_display(text: &str) -> (Vec<Mode>, Option<u32>, Vec<String>) {
    let mut modes: Vec<Mode> = Vec::new();
    for part in text.split("{id=").skip(1) {
        let body = part.split('}').next().unwrap_or("");
        let id = body.split(',').next().and_then(|v| v.trim().parse().ok());
        let num = |k: &str| {
            body.split(", ")
                .find_map(|kv| kv.trim().strip_prefix(k)?.strip_prefix('='))
        };
        if let (Some(id), Some(w), Some(h), Some(f)) = (
            id,
            num("width").and_then(|v| v.parse().ok()),
            num("height").and_then(|v| v.parse().ok()),
            num("fps").and_then(|v| v.parse().ok()),
        ) && !modes.iter().any(|m| m.id == id)
        {
            modes.push(Mode {
                id,
                width: w,
                height: h,
                fps: f,
            });
        }
    }
    modes.sort_by(|a, b| {
        (b.width * b.height)
            .cmp(&(a.width * a.height))
            .then(b.fps.total_cmp(&a.fps))
    });
    let active = text
        .lines()
        .find_map(|l| l.trim().strip_prefix("mActiveModeId=")?.trim().parse().ok());
    let mut hdr = Vec::new();
    if let Some(list) = text
        .split("mSupportedHdrTypes=[")
        .nth(1)
        .and_then(|r| r.split(']').next())
    {
        for t in list.split(',').filter_map(|x| x.trim().parse::<u32>().ok()) {
            let name = match t {
                1 => "Dolby Vision",
                2 => "HDR10",
                3 => "HLG",
                4 => "HDR10+",
                _ => continue,
            };
            if !hdr.iter().any(|h| h == name) {
                hdr.push(name.to_string());
            }
        }
    }
    (modes, active, hdr)
}

/// The output device of the media stream in `dumpsys audio` ("speaker", "hdmi_arc"…), made
/// readable.
pub fn parse_audio_device(text: &str) -> Option<String> {
    let mut in_music = false;
    for l in text.lines() {
        if l.starts_with("- STREAM_") {
            in_music = l.starts_with("- STREAM_MUSIC");
            continue;
        }
        if in_music && let Some(d) = l.trim().strip_prefix("Devices:") {
            return Some(readable_device(d.trim()));
        }
    }
    text.lines()
        .find_map(|l| l.trim().strip_prefix("Devices:"))
        .map(|d| readable_device(d.trim()))
}

fn readable_device(d: &str) -> String {
    let first = d.split_whitespace().next().unwrap_or(d);
    match first {
        "speaker" => "TV speakers".into(),
        "hdmi" => "HDMI".into(),
        "hdmi_arc" | "hdmi_earc" => "Soundbar or receiver (HDMI ARC)".into(),
        "spdif" => "Optical (S/PDIF)".into(),
        "bt_a2dp" | "bt_a2dp_hp" | "bt_a2dp_spk" => "Bluetooth".into(),
        "line" | "wired_headphone" | "wired_headset" => "Headphones / line out".into(),
        other => other.to_string(),
    }
}

/// encoded_surround_output → words.
pub fn passthrough_name(v: Option<u8>) -> &'static str {
    match v {
        None | Some(0) => "Auto",
        Some(1) => "Never (the TV decodes)",
        Some(2) => "Always",
        Some(3) => "Manual",
        Some(_) => "Unknown",
    }
}

/// encoded_surround_output_enabled_formats ("5,6,18") → names.
pub fn surround_formats(list: &str) -> Vec<String> {
    list.split(',')
        .filter_map(|x| x.trim().parse::<u32>().ok())
        .map(|c| {
            match c {
                5 => "Dolby Digital (AC-3)",
                6 => "Dolby Digital Plus (E-AC-3)",
                7 => "DTS",
                8 => "DTS-HD",
                14 => "Dolby TrueHD",
                17 => "Dolby AC-4",
                18 => "Dolby Atmos (E-AC-3 JOC)",
                19 => "Dolby MAT",
                _ => "",
            }
            .to_string()
        })
        .filter(|s| !s.is_empty())
        .collect()
}

/// The Widevine reader's output (`key=value` lines, see tui/drminfo/DrmInfo.java).
pub fn parse_drm(text: &str) -> Drm {
    let mut d = Drm::default();
    for l in text.lines() {
        let Some((k, v)) = l.trim().split_once('=') else {
            continue;
        };
        let v = v.trim().to_string();
        match k {
            "widevine" => {
                d.read = true;
                d.widevine = v == "yes";
            }
            "playready" => d.playready = v == "yes",
            "securityLevel" => d.level = Some(v),
            "version" => d.version = Some(v),
            "systemId" => d.system_id = Some(v),
            "hdcpLevel" => d.hdcp = Some(v),
            "maxHdcpLevel" => d.max_hdcp = Some(v),
            "error" => d.error = Some(v),
            _ => {}
        }
    }
    d
}

/// HDCP as people say it: "HDCP 2.2"; "Disconnected" means the picture isn't going out over HDMI
/// (a TV's own screen), so HDCP isn't used.
pub fn hdcp_words(v: &str) -> String {
    match v {
        "Disconnected" => "Not used (built-in screen)".into(),
        "Unprotected" | "HDCP-NONE" | "None" => "None".into(),
        other => other.replace("HDCPv", "HDCP ").replace("HDCP-", "HDCP "),
    }
}

/// Netflix's own DRM plugin or properties on the TV: a Netflix-partnered build.
pub fn netflix_partner(plugin_list: &str, getprop: &str) -> bool {
    plugin_list.contains("libnetflix")
        || getprop
            .lines()
            .any(|l| l.contains(".nrdp.") || l.contains("ro.netflix."))
}

#[cfg(test)]
mod tests {
    use super::*;

    const CODECS: &str = include_str!("testdata/media-codecs.xml");
    const DISPLAY: &str = include_str!("testdata/dumpsys-display.txt");
    const AUDIO: &str = include_str!("testdata/dumpsys-audio.txt");

    #[test]
    fn video_formats_on_a_mi_tv() {
        let d = parse_decoders(CODECS);
        let got: Vec<Decode> = VIDEO.iter().map(|(_, m)| decode_for(&d, m)).collect();
        // H.264, HEVC, VP9, AV1, Dolby Vision, MPEG-2 all have the chip's own (OMX.MS) decoders.
        assert_eq!(got[0], Decode::Hardware);
        assert_eq!(got[1], Decode::Hardware);
        assert_eq!(got[2], Decode::Hardware);
        assert_eq!(got[4], Decode::Hardware);
        assert_eq!(got[5], Decode::Hardware);
        assert!(d.keys().all(|k| !k.is_empty()));
        // Encoders don't count as decoders.
        assert!(
            !d.values()
                .flatten()
                .any(|n| n.to_lowercase().contains("encoder"))
        );
    }

    #[test]
    fn software_and_missing_decoders() {
        let xml = r#"<MediaCodecs><Decoders>
            <MediaCodec name="c2.android.av1.decoder" type="video/av01" />
            <MediaCodec name="OMX.vendor.vp9" software-codec="true"><Type name="video/x-vnd.on2.vp9" /></MediaCodec>
            </Decoders><Encoders><MediaCodec name="OMX.enc.hevc" type="video/hevc" /></Encoders></MediaCodecs>"#;
        let d = parse_decoders(xml);
        assert_eq!(decode_for(&d, &["video/av01"]), Decode::Software);
        assert_eq!(decode_for(&d, &["video/x-vnd.on2.vp9"]), Decode::Software);
        assert_eq!(decode_for(&d, &["video/hevc"]), Decode::NotListed); // encoder only
    }

    #[test]
    fn screen_modes_on_a_mi_tv() {
        let (modes, active, hdr) = parse_display(DISPLAY);
        assert_eq!(
            modes,
            vec![Mode {
                id: 1,
                width: 1920,
                height: 1080,
                fps: 60.000004
            }]
        );
        assert_eq!(active, Some(1));
        assert!(hdr.is_empty()); // this TV doesn't report HDR types
    }

    #[test]
    fn modes_sorted_and_hdr_named() {
        let t = "supportedModes [{id=1, width=1920, height=1080, fps=60.0}, {id=2, width=3840, height=2160, fps=60.0}, \
                 {id=3, width=3840, height=2160, fps=23.976}]\n    mActiveModeId=2\n  mSupportedHdrTypes=[2, 3, 1]\n";
        let (modes, active, hdr) = parse_display(t);
        assert_eq!(
            modes.iter().map(|m| m.id).collect::<Vec<_>>(),
            vec![2, 3, 1]
        );
        assert_eq!(active, Some(2));
        assert_eq!(hdr, vec!["HDR10", "HLG", "Dolby Vision"]);
    }

    #[test]
    fn sound_on_a_mi_tv() {
        assert_eq!(parse_audio_device(AUDIO).as_deref(), Some("TV speakers"));
        assert_eq!(passthrough_name(None), "Auto");
        assert_eq!(passthrough_name(Some(2)), "Always");
        assert_eq!(
            surround_formats("5, 6,18,999"),
            vec![
                "Dolby Digital (AC-3)",
                "Dolby Digital Plus (E-AC-3)",
                "Dolby Atmos (E-AC-3 JOC)"
            ]
        );
    }

    #[test]
    fn drm_from_tv_home_helper() {
        let d = parse_drm(
            "widevine=yes\nplayready=yes\nsecurityLevel=L1\nversion=15.0.0\nsystemId=18298\nhdcpLevel=Disconnected\nmaxHdcpLevel=Disconnected\n",
        );
        assert!(d.read && d.widevine && d.playready);
        assert_eq!(d.level.as_deref(), Some("L1"));
        assert_eq!(d.system_id.as_deref(), Some("18298"));
        assert_eq!(hdcp_words("Disconnected"), "Not used (built-in screen)");
        assert_eq!(hdcp_words("HDCPv2.2"), "HDCP 2.2");
        let none = parse_drm("Error: Could not find class\n");
        assert!(!none.read);
    }

    #[test]
    fn netflix_partner_build() {
        assert!(netflix_partner(
            "libnetflixmediadrmplugin.so\nlibwvdrmengine.so\n",
            ""
        ));
        assert!(netflix_partner(
            "",
            "[vendor.nrdp.suppress-notification]: [0]\n"
        ));
        assert!(!netflix_partner(
            "libwvdrmengine.so\n",
            "[ro.product.model]: [x]\n"
        ));
    }

    #[test]
    fn broken_input_gives_nothing() {
        assert!(parse_decoders("garbage").is_empty());
        assert_eq!(parse_display(""), (Vec::new(), None, Vec::new()));
        assert_eq!(parse_audio_device(""), None);
        assert_eq!(parse_drm(""), Drm::default());
    }
}
