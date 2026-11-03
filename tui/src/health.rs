//! The Health screen: memory, storage, CPU, network, temperature and the apps using the most
//! memory, parsed from read-only adb output. Pure: no adb here (the worker reads, this parses).

/// How worrying a reading is, for its colour.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    Fine,
    High,
    VeryHigh,
}

impl Level {
    /// From a percentage: 75% or more is high, 90% or more very high.
    pub fn of_percent(p: f64) -> Level {
        if p >= 90.0 {
            Level::VeryHigh
        } else if p >= 75.0 {
            Level::High
        } else {
            Level::Fine
        }
    }

    /// From a temperature in °C: above 60 is warm, 76 or more hot.
    pub fn of_celsius(c: f64) -> Level {
        if c >= 76.0 {
            Level::VeryHigh
        } else if c > 60.0 {
            Level::High
        } else {
            Level::Fine
        }
    }
}

/// Used and total, in bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Usage {
    pub used: u64,
    pub total: u64,
}

impl Usage {
    pub fn percent(&self) -> f64 {
        if self.total == 0 {
            0.0
        } else {
            self.used as f64 * 100.0 / self.total as f64
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Temperature {
    pub celsius: f64,
    /// "CPU", "GPU", "Skin"… or the sensor's own name.
    pub sensor: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct NetRate {
    pub iface: String,
    /// Bytes per second.
    pub down: u64,
    pub up: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MemUser {
    /// The process name (a package name for apps).
    pub process: String,
    pub bytes: u64,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Health {
    pub ram: Option<Usage>,
    pub swap: Option<Usage>,
    pub storage: Option<Usage>,
    pub cpu_percent: Option<f64>,
    pub net: Vec<NetRate>,
    pub temperature: Option<Temperature>,
    pub top_memory: Vec<MemUser>,
}

/// `/proc/meminfo`: RAM used (total − available) and swap used (total − free), in bytes.
pub fn parse_proc_meminfo(text: &str) -> (Option<Usage>, Option<Usage>) {
    let kb = |key: &str| -> Option<u64> {
        text.lines().find_map(|l| {
            let rest = l.strip_prefix(key)?.strip_prefix(':')?;
            rest.split_whitespace().next()?.parse::<u64>().ok()
        })
    };
    let ram = match (kb("MemTotal"), kb("MemAvailable").or(kb("MemFree"))) {
        (Some(t), Some(a)) if t > 0 => Some(Usage {
            used: t.saturating_sub(a) * 1024,
            total: t * 1024,
        }),
        _ => None,
    };
    let swap = match (kb("SwapTotal"), kb("SwapFree")) {
        (Some(t), Some(f)) if t > 0 => Some(Usage {
            used: t.saturating_sub(f) * 1024,
            total: t * 1024,
        }),
        _ => None,
    };
    (ram, swap)
}

/// `df -h /data`: the row mounted on /data, sizes like 11G / 4.2G / 512M.
pub fn parse_df_data(text: &str) -> Option<Usage> {
    for l in text.lines() {
        let f: Vec<&str> = l.split_whitespace().collect();
        if f.len() >= 6 && f[f.len() - 1] == "/data" {
            let size = human_bytes(f[f.len() - 5])?;
            let used = human_bytes(f[f.len() - 4])?;
            return Some(Usage { used, total: size });
        }
    }
    None
}

/// "11G", "4.2G", "512M", "900K", "123" (bytes) → bytes.
fn human_bytes(s: &str) -> Option<u64> {
    let (num, unit) = match s.char_indices().find(|(_, c)| c.is_ascii_alphabetic()) {
        Some((i, _)) => (&s[..i], s[i..].to_ascii_uppercase()),
        None => (s, String::new()),
    };
    let n: f64 = num.parse().ok()?;
    let mult: f64 = match unit.trim_end_matches('B').trim_end_matches('I') {
        "" => 1.0,
        "K" => 1024.0,
        "M" => 1024.0 * 1024.0,
        "G" => 1024.0 * 1024.0 * 1024.0,
        "T" => 1024.0 * 1024.0 * 1024.0 * 1024.0,
        _ => return None,
    };
    Some((n * mult) as u64)
}

/// The aggregate `cpu ` line of /proc/stat: (busy, total) jiffies.
fn cpu_times(stat: &str) -> Option<(u64, u64)> {
    let l = stat.lines().find(|l| l.starts_with("cpu "))?;
    let v: Vec<u64> = l
        .split_whitespace()
        .skip(1)
        .filter_map(|x| x.parse().ok())
        .collect();
    if v.len() < 4 {
        return None;
    }
    let total: u64 = v.iter().sum();
    let idle = v[3] + v.get(4).copied().unwrap_or(0); // idle + iowait
    Some((total - idle, total))
}

/// /proc/net/dev: interface → (received, sent) bytes, loopback left out.
fn net_bytes(dev: &str) -> Vec<(String, u64, u64)> {
    dev.lines()
        .filter_map(|l| {
            let (name, rest) = l.split_once(':')?;
            let name = name.trim();
            if name.is_empty() || name == "lo" || name.contains('|') {
                return None;
            }
            let f: Vec<u64> = rest
                .split_whitespace()
                .filter_map(|x| x.parse().ok())
                .collect();
            if f.len() < 9 {
                return None;
            }
            Some((name.to_string(), f[0], f[8]))
        })
        .collect()
}

/// Two `cat /proc/stat; cat /proc/net/dev` samples a second apart (in one text, in that order):
/// CPU use in percent, and each interface's rate (only ones that moved).
pub fn parse_sample(text: &str, seconds: f64) -> (Option<f64>, Vec<NetRate>) {
    // Split into the two samples at the second "cpu " line.
    let starts: Vec<usize> = text
        .match_indices("\ncpu ")
        .map(|(i, _)| i + 1)
        .chain(text.starts_with("cpu ").then_some(0))
        .collect();
    let mut starts = starts;
    starts.sort_unstable();
    if starts.len() < 2 {
        return (None, Vec::new());
    }
    let (a, b) = text.split_at(starts[1]);
    let cpu = match (cpu_times(a), cpu_times(b)) {
        (Some((b1, t1)), Some((b2, t2))) if t2 > t1 => {
            Some(((b2.saturating_sub(b1)) as f64 * 100.0 / (t2 - t1) as f64).clamp(0.0, 100.0))
        }
        _ => None,
    };
    let before = net_bytes(a);
    let mut net = Vec::new();
    for (name, rx, tx) in net_bytes(b) {
        if let Some((_, rx0, tx0)) = before.iter().find(|(n, _, _)| *n == name) {
            let down = (rx.saturating_sub(*rx0) as f64 / seconds) as u64;
            let up = (tx.saturating_sub(*tx0) as f64 / seconds) as u64;
            if down > 0 || up > 0 {
                net.push(NetRate {
                    iface: name,
                    down,
                    up,
                });
            }
        }
    }
    net.sort_by_key(|n| std::cmp::Reverse(n.down + n.up));
    (cpu, net)
}

/// `dumpsys thermalservice`: the hottest believable sensor (10–120 °C), current values first.
pub fn parse_thermal(text: &str) -> Option<Temperature> {
    let mut best: Option<Temperature> = None;
    for rec in text.split("Temperature{").skip(1) {
        let rec = rec.split('}').next().unwrap_or("");
        let field = |k: &str| {
            rec.split(", ")
                .find_map(|kv| kv.trim().strip_prefix(k)?.strip_prefix('='))
                .map(str::trim)
        };
        let Some(c) = field("mValue").and_then(|v| v.parse::<f64>().ok()) else {
            continue;
        };
        let kind = field("mType")
            .and_then(|v| v.parse::<i32>().ok())
            .unwrap_or(-1);
        if matches!(kind, 6..=8) || !(10.0..=120.0).contains(&c) {
            continue; // battery current/voltage sensors, or nonsense
        }
        let sensor = match kind {
            0 => "CPU".to_string(),
            1 => "GPU".to_string(),
            3 => "Skin".to_string(),
            4 => "USB port".to_string(),
            _ => field("mName").unwrap_or("Sensor").to_string(),
        };
        if best.as_ref().is_none_or(|b| c > b.celsius) {
            best = Some(Temperature { celsius: c, sensor });
        }
    }
    best
}

/// `dumpsys hardware_properties` "CPU temperatures: [45.0, 47.5]" and the like, when the thermal
/// service has nothing.
pub fn parse_hardware_properties(text: &str) -> Option<Temperature> {
    let mut best: Option<Temperature> = None;
    for (label, sensor) in [
        ("CPU temperatures:", "CPU"),
        ("GPU temperatures:", "GPU"),
        ("Skin temperatures:", "Skin"),
    ] {
        let Some(l) = text.lines().find(|l| l.trim_start().starts_with(label)) else {
            continue;
        };
        let inside = l
            .split_once('[')
            .and_then(|(_, r)| r.split_once(']'))
            .map(|(i, _)| i);
        for v in inside
            .unwrap_or("")
            .split(',')
            .filter_map(|x| x.trim().parse::<f64>().ok())
        {
            if (10.0..=120.0).contains(&v) && best.as_ref().is_none_or(|b| v > b.celsius) {
                best = Some(Temperature {
                    celsius: v,
                    sensor: sensor.to_string(),
                });
            }
        }
    }
    best
}

/// `dumpsys meminfo` "Total PSS by process:" — the biggest `n`.
pub fn parse_top_memory(text: &str, n: usize) -> Vec<MemUser> {
    let mut out = Vec::new();
    let mut inside = false;
    for l in text.lines() {
        if l.starts_with("Total PSS by process") {
            inside = true;
            continue;
        }
        if !inside {
            continue;
        }
        let t = l.trim();
        if t.is_empty() {
            if !out.is_empty() {
                break;
            }
            continue;
        }
        let Some((size, rest)) = t.split_once(':') else {
            break;
        };
        let Some(kb) = size
            .trim()
            .trim_end_matches('K')
            .replace(',', "")
            .parse::<u64>()
            .ok()
        else {
            break;
        };
        let process = rest
            .trim()
            .split(" (pid")
            .next()
            .unwrap_or("")
            .trim()
            .to_string();
        out.push(MemUser {
            process,
            bytes: kb * 1024,
        });
        if out.len() >= n {
            break;
        }
    }
    out
}

/// "1.2 GB", "530 MB", "12 KB", "800 B" (powers of 1024).
pub fn bytes(n: u64) -> String {
    const U: [&str; 4] = ["KB", "MB", "GB", "TB"];
    if n < 1024 {
        return format!("{n} B");
    }
    let mut v = n as f64 / 1024.0;
    let mut i = 0;
    while v >= 1024.0 && i < U.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if v >= 100.0 || i == 0 {
        format!("{:.0} {}", v, U[i])
    } else {
        format!("{:.1} {}", v, U[i])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MEMINFO: &str = include_str!("testdata/proc-meminfo.txt");
    const DUMPSYS_MEMINFO: &str = include_str!("testdata/dumpsys-meminfo.txt");
    const DF: &str = include_str!("testdata/df-data.txt");
    const SAMPLE: &str = include_str!("testdata/stat-net-sample.txt");
    const THERMAL_EMPTY: &str = include_str!("testdata/thermalservice.txt");
    const HW_EMPTY: &str = include_str!("testdata/hardware-properties.txt");

    #[test]
    fn memory_from_a_mi_tv() {
        let (ram, swap) = parse_proc_meminfo(MEMINFO);
        let ram = ram.unwrap();
        assert!(
            ram.total > 1_500_000_000 && ram.total < 2_200_000_000,
            "{ram:?}"
        ); // a 2 GB TV
        assert!(ram.used > 0 && ram.used < ram.total);
        assert!(swap.unwrap().total > 0); // zram
    }

    #[test]
    fn storage_from_df() {
        let s = parse_df_data(DF).unwrap();
        assert_eq!(s.total, 11 * 1024 * 1024 * 1024);
        assert_eq!(s.used, (4.2 * 1024.0 * 1024.0 * 1024.0) as u64);
        assert!((s.percent() - 38.0).abs() < 1.0);
    }

    #[test]
    fn cpu_and_network_from_two_samples() {
        let (cpu, net) = parse_sample(SAMPLE, 1.0);
        let cpu = cpu.unwrap();
        assert!((0.0..=100.0).contains(&cpu), "{cpu}");
        assert!(net.iter().all(|n| n.iface != "lo")); // loopback left out
        assert!(net.iter().any(|n| n.iface == "wlan0"), "{net:?}");
    }

    #[test]
    fn this_tv_reports_no_temperature() {
        assert_eq!(parse_thermal(THERMAL_EMPTY), None);
        assert_eq!(parse_hardware_properties(HW_EMPTY), None);
    }

    #[test]
    fn hottest_believable_sensor() {
        let t = "Current temperatures from HAL:\n\tTemperature{mValue=48.5, mType=0, mName=cpu0, mStatus=0}\n\
                 \tTemperature{mValue=52.0, mType=1, mName=gpu, mStatus=0}\n\
                 \tTemperature{mValue=4000.0, mType=7, mName=battery, mStatus=0}\n\
                 \tTemperature{mValue=-273.0, mType=3, mName=skin, mStatus=0}\n";
        assert_eq!(
            parse_thermal(t),
            Some(Temperature {
                celsius: 52.0,
                sensor: "GPU".into()
            })
        );
        let hw =
            "CPU temperatures: [45.0, 61.5]\nGPU temperatures: []\nSkin temperatures: [38.0]\n";
        assert_eq!(
            parse_hardware_properties(hw),
            Some(Temperature {
                celsius: 61.5,
                sensor: "CPU".into()
            })
        );
    }

    #[test]
    fn biggest_memory_users() {
        let top = parse_top_memory(DUMPSYS_MEMINFO, 8);
        assert_eq!(top.len(), 8);
        assert_eq!(top[0].process, "my.rafhan.app.tvhome");
        assert_eq!(top[0].bytes, 130_085 * 1024);
        assert!(top.windows(2).all(|w| w[0].bytes >= w[1].bytes));
    }

    #[test]
    fn levels_and_sizes() {
        assert_eq!(Level::of_percent(74.9), Level::Fine);
        assert_eq!(Level::of_percent(75.0), Level::High);
        assert_eq!(Level::of_percent(95.0), Level::VeryHigh);
        assert_eq!(Level::of_celsius(60.0), Level::Fine);
        assert_eq!(Level::of_celsius(70.0), Level::High);
        assert_eq!(Level::of_celsius(80.0), Level::VeryHigh);
        assert_eq!(bytes(800), "800 B");
        assert_eq!(bytes(1536), "2 KB"); // KB without decimals
        assert_eq!(bytes(5 * 1024 * 1024 + 300 * 1024), "5.3 MB");
        assert_eq!(bytes(1_288_490_189), "1.2 GB");
    }

    #[test]
    fn broken_input_gives_nothing() {
        for bad in ["", "garbage", "cpu x y z"] {
            assert_eq!(parse_proc_meminfo(bad), (None, None));
            assert_eq!(parse_df_data(bad), None);
            assert_eq!(parse_sample(bad, 1.0), (None, Vec::new()));
            assert!(parse_top_memory(bad, 5).is_empty());
        }
    }
}
