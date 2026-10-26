//! Runout's Android adapter for the pinned MIT Soulseek protocol client.
//! No UI, account generation, public-network tests, or Android URI path guessing.
use jni::{JNIEnv, objects::{GlobalRef, JObject, JString, JValue}, sys::{jlong, jstring}};
use serde_json::{Value, json};
use soulseek_rs::{Client, ClientSettings, ClientVersion, PeerAddress, DownloadStatus, SessionLoss};
use soulseek_rs::shares::{HostFileOpener, HostSharedFile};
use std::{collections::{HashMap, HashSet, VecDeque}, fs::File, io::{self, Read, Seek, SeekFrom},
    os::fd::FromRawFd, path::PathBuf, sync::{Arc, Mutex, MutexGuard, OnceLock,
    atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering}, mpsc::Receiver}, time::{Duration, Instant}};

type OpenFile = Arc<dyn Fn(&str) -> io::Result<File> + Send + Sync>;
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> { m.lock().unwrap_or_else(|p| p.into_inner()) }
fn field<'a>(value: &'a Value, key: &str) -> Result<&'a str, String> {
    value.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
        .ok_or_else(|| format!("Missing {key}"))
}
/// FLAC files a search collects before it stops: with "flac" in the query nearly every file is a
/// match, and a popular album easily fills 5,000 before the network has finished answering.
const MAX_SEARCH_FLAC: usize = 15_000;

fn flac_path(path: &str) -> bool {
    path.len() <= 4096 && path.to_ascii_lowercase().ends_with(".flac")
        && !path.contains('\0') && path.split(['/', '\\']).all(|s| s != ".." && s != ".")
}
fn valid_username(name: &str) -> bool {
    !name.is_empty() && name.len() <= 30 && name.trim() == name
        && name.bytes().all(|b| (0x20..=0x7e).contains(&b))
}

/// Check native FLAC's mandatory first STREAMINFO block before exposing a file.
/// Full decode validation remains Android's responsibility before library import.
fn check_flac(file: &mut File) -> io::Result<(u32, u32, u32)> {
    file.seek(SeekFrom::Start(0))?;
    let mut prefix = [0u8; 10];
    file.read_exact(&mut prefix)?;
    if &prefix[..3] == b"ID3" {
        if prefix[6..10].iter().any(|b| b & 0x80 != 0) {
            return Err(io::ErrorKind::InvalidData.into());
        }
        let size = prefix[6..10].iter().fold(0u64, |n, b| (n << 7) | u64::from(*b));
        if size > 24 * 1024 * 1024 { return Err(io::ErrorKind::InvalidData.into()); }
        let footer = if prefix[5] & 0x10 != 0 { 10 } else { 0 };
        file.seek(SeekFrom::Start(10 + size + footer))?;
    } else { file.seek(SeekFrom::Start(0))?; }
    let mut header = [0u8; 42];
    file.read_exact(&mut header)?;
    if &header[..4] != b"fLaC" || header[4] & 0x7f != 0 || header[5..8] != [0, 0, 34] {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "The file is not native FLAC"));
    }
    let stream = u64::from_be_bytes(header[18..26].try_into().unwrap());
    let rate = (stream >> 44) as u32;
    let depth = ((stream >> 36) & 31) as u32 + 1;
    let samples = stream & 0x0f_ffff_ffff;
    let min_block = u16::from_be_bytes([header[8], header[9]]);
    let max_block = u16::from_be_bytes([header[10], header[11]]);
    if rate == 0 || rate > 655_350 || !(4..=32).contains(&depth) || min_block < 16 || max_block < min_block {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Invalid FLAC STREAMINFO"));
    }
    file.seek(SeekFrom::Start(0))?;
    Ok((rate, depth, (samples / u64::from(rate)).min(u64::from(u32::MAX)) as u32))
}

struct Transfer {
    username: String, path: String, total: u64, directory: PathBuf,
    receiver: Receiver<DownloadStatus>, state: DownloadStatus,
    last: String, verified: bool, token: u32,
}
struct Runtime {
    client: Mutex<Option<Arc<Client>>>, connecting: Mutex<Option<soulseek_rs::CancelHandle>>,
    events: Mutex<VecDeque<Value>>, generation: AtomicU64, closed: AtomicBool,
    searches: Mutex<HashMap<String, Arc<AtomicBool>>>, transfers: Mutex<HashMap<String, Transfer>>,
    contacts: Mutex<HashMap<String, Option<bool>>>, open: OpenFile,
    share_entries: Mutex<Vec<HostSharedFile>>, seen_loss: AtomicBool,
    uploads: Mutex<HashMap<String, String>>,
    /// Transfers dropped after their final state was reported, newest last. A pause, resume
    /// or cancel that races the report still finds nothing to do instead of an unknown id.
    retired: Mutex<VecDeque<String>>,
}
/// How many finished transfer ids are remembered for those late commands.
const RETIRED_LIMIT: usize = 256;

impl Runtime {
    fn new(open: OpenFile) -> Arc<Self> {
        Arc::new(Self { client: Mutex::new(None), connecting: Mutex::new(None),
            events: Mutex::new(VecDeque::new()), generation: AtomicU64::new(0), closed: AtomicBool::new(false),
            searches: Mutex::new(HashMap::new()), transfers: Mutex::new(HashMap::new()),
            contacts: Mutex::new(HashMap::new()), open, share_entries: Mutex::new(Vec::new()),
            seen_loss: AtomicBool::new(false), uploads: Mutex::new(HashMap::new()),
            retired: Mutex::new(VecDeque::new()) })
    }
    fn push(&self, event: Value) {
        let mut events = lock(&self.events);
        // Peer discovery is bounded. Drop an old discovery/progress event first;
        // DMs are read directly during poll and never acknowledged here.
        if events.len() >= 4096 {
            if let Some(index) = events.iter().position(|e| matches!(e["type"].as_str(), Some("search_result" | "upload"))) {
                events.remove(index);
            } else { return; }
        }
        events.push_back(event);
    }
    fn client(&self) -> Result<Arc<Client>, String> {
        let client = lock(&self.client).clone().ok_or("Sign in to Soulseek first")?;
        if client.session_loss().is_some() { return Err("The Soulseek connection was lost".into()); }
        Ok(client)
    }
    fn stop(&self) {
        self.generation.fetch_add(1, Ordering::SeqCst);
        if let Some(cancel) = lock(&self.connecting).take() { cancel.cancel(); }
        for search in lock(&self.searches).values() { search.store(true, Ordering::Relaxed); }
        lock(&self.searches).clear();
        if let Some(client) = lock(&self.client).take() {
            // Removing the token makes active/paused download workers exit on
            // their next status check. Do not cancel: Cancelled discards .part.
            for transfer in lock(&self.transfers).values() {
                let _ = client.remove_download(&transfer.username, &transfer.path);
            }
            client.shutdown();
        }
        lock(&self.transfers).clear();
        lock(&self.retired).clear();
        lock(&self.uploads).clear();
        lock(&self.contacts).clear();
        lock(&self.share_entries).clear();
        lock(&self.events).clear();
    }
    fn publish_shares(&self, client: &Client, entries: Vec<HostSharedFile>) -> Result<(), String> {
        let allowed: HashMap<String, u64> = entries.iter().map(|f| (f.key.clone(), f.size)).collect();
        let open = self.open.clone();
        let opener: HostFileOpener = Arc::new(move |key| {
            let key = key.to_str().ok_or(io::ErrorKind::InvalidInput)?;
            let size = allowed.get(key).ok_or(io::ErrorKind::PermissionDenied)?;
            let mut file = open(key)?;
            check_flac(&mut file)?;
            if file.metadata()?.len() != *size { return Err(io::ErrorKind::InvalidData.into()); }
            Ok(file)
        });
        client.set_host_shares(entries, opener).map_err(|e| e.to_string())
    }
    fn execute(self: &Arc<Self>, value: Value) -> Result<Value, String> {
        if self.closed.load(Ordering::Relaxed) { return Err("Native session is closed".into()); }
        let op = field(&value, "op")?;
        match op {
            "login" => {
                let username = field(&value, "username")?.to_string();
                let password = field(&value, "password")?.to_string();
                if !valid_username(&username) { return Err("Use 1–30 printable ASCII characters without surrounding spaces".into()); }
                if password.len() > 1024 { return Err("Password is too long".into()); }
                self.stop();
                let generation = self.generation.load(Ordering::SeqCst);
                self.seen_loss.store(false, Ordering::Relaxed);
                let settings = ClientSettings { username: username.clone(), password,
                    server_address: PeerAddress::new(value["host"].as_str().unwrap_or("server.slsknet.org").to_string(),
                        value["port"].as_u64().filter(|p| *p > 0 && *p <= 65535).unwrap_or(2416) as u16),
                    enable_listen: true, listen_port: 0, accept_children: false,
                    shared_directories: Vec::new(), version: ClientVersion { major: 177, minor: 1 } };
                let mut client = Client::with_settings(settings);
                *lock(&self.connecting) = Some(client.cancel_handle());
                self.push(json!({"type":"session","state":"connecting","username":username}));
                let runtime = self.clone();
                std::thread::spawn(move || {
                    let outcome = client.connect().and_then(|()| client.login());
                    if runtime.generation.load(Ordering::SeqCst) != generation { client.shutdown(); return; }
                    lock(&runtime.connecting).take();
                    match outcome {
                        Ok(true) => {
                            client.set_upload_slots(2);
                            // A popular search draws thousands of responders through the server's
                            // relay. Search-only connections now close two seconds after their
                            // results, so the room turns over quickly; 48 seats with a 10 s hold
                            // turned all but the first few dozen away.
                            client.set_max_peers(256);
                            client.set_peer_admission_grace(Duration::from_secs(2));
                            *lock(&runtime.client) = Some(Arc::new(client));
                            runtime.push(json!({"type":"session","state":"connected","username":username}));
                        }
                        other => {
                            let reason_code = if matches!(&other, Ok(false) | Err(soulseek_rs::error::SoulseekRs::AuthenticationFailed)) { "AUTH_REJECTED" } else { "NETWORK_LOST" };
                            let reason = match other { Ok(false) => "The Soulseek username or password was rejected".to_string(), Err(e) => e.to_string(), _ => unreachable!() };
                            client.shutdown();
                            runtime.push(json!({"type":"session","state":"error","reason":reason,"reasonCode":reason_code}));
                        }
                    }
                });
            }
            "logout" => { self.stop(); self.push(json!({"type":"session","state":"disconnected"})); }
            "search" => {
                let client = self.client()?;
                let id = field(&value, "id")?.to_string();
                let query = field(&value, "query")?.trim().to_string();
                if query.len() < 2 || query.len() > 256 { return Err("Search must contain 2–256 characters".into()); }
                // What goes on the wire: the cleaned-up terms (the query itself when not given), and
                // the variants to widen to when no one answers at all. The server silently drops
                // searches for some names; leaving one word out gets past it, and the app still
                // keeps only folders that match every word.
                let strings = |key: &str| -> Vec<String> {
                    value[key].as_array().map(|a| a.iter().filter_map(Value::as_str).map(|s| s.trim().to_string())
                        .filter(|s| s.len() >= 2 && s.len() <= 256).take(6).collect()).unwrap_or_default()
                };
                let mut queries = strings("queries"); if queries.is_empty() { queries.push(query.clone()); }
                let variants: Vec<String> = strings("variants").into_iter().filter(|v| !queries.contains(v)).collect();
                let widen_after = Duration::from_millis(value["widenAfterMs"].as_u64().unwrap_or(0).min(30_000));
                // Only one active network search: duplicate query tokens in the
                // upstream query-keyed index must never cross result identities.
                for flag in lock(&self.searches).values() { flag.store(true, Ordering::Relaxed); }
                lock(&self.searches).clear();
                let cancelled = Arc::new(AtomicBool::new(false));
                lock(&self.searches).insert(id.clone(), cancelled.clone());
                for q in &queries { client.start_search(q).map_err(|e| e.to_string())?; }
                let mut duration = Duration::from_millis(value["timeoutMs"].as_u64().unwrap_or(12000).clamp(250, 60_000));
                let runtime = self.clone();
                let generation = self.generation.load(Ordering::SeqCst);
                std::thread::spawn(move || {
                    let started = Instant::now();
                    let admitted_before = soulseek_rs::types::PEERS_ADMITTED.load(Ordering::Relaxed);
                    let refused_before = soulseek_rs::types::PEERS_REFUSED.load(Ordering::Relaxed);
                    let mut seen = HashSet::new();
                    let mut raw_files = 0usize;
                    let mut replies = 0usize;
                    let mut active = queries.clone();
                    let mut widened = false;
                    while started.elapsed() < duration && !cancelled.load(Ordering::Relaxed)
                        && runtime.generation.load(Ordering::SeqCst) == generation && seen.len() < MAX_SEARCH_FLAC {
                        if !widened && !variants.is_empty() && !widen_after.is_zero() && replies == 0 && started.elapsed() >= widen_after {
                            widened = true;
                            for v in &variants { if client.start_search(v).is_ok() { active.push(v.clone()); } }
                            duration += Duration::from_secs(20);
                            runtime.push(json!({"type":"search_widened","id":id,"queries":variants}));
                        }
                        let mut batch = Vec::new();
                        for response in active.iter().flat_map(|q| client.take_search_results(q)) {
                            replies += 1;
                            raw_files += response.files.len();
                            for file in response.files {
                                if seen.len() >= MAX_SEARCH_FLAC { break; }
                                if flac_path(&file.name) && seen.insert((response.username.clone(), file.name.clone(), file.size)) {
                                    let mut event = file_json(&file.name, file.size, &file.attribs);
                                    event["username"] = json!(response.username); event["freeSlots"] = json!(response.slots);
                                    event["uploadSpeed"] = json!(response.speed); event["queueLength"] = json!(response.queue_length);
                                    batch.push(event);
                                    if batch.len() == 100 {
                                        runtime.push(json!({"type":"search_batch","id":id,"files":batch}));
                                        batch = Vec::new();
                                    }
                                }
                            }
                        }
                        if !batch.is_empty() { runtime.push(json!({"type":"search_batch","id":id,"files":batch})); }
                        std::thread::sleep(Duration::from_millis(100));
                    }
                    if runtime.generation.load(Ordering::SeqCst) == generation {
                        runtime.push(json!({"type":"search_done","id":id,"cancelled":cancelled.load(Ordering::Relaxed),"limited":seen.len()>=MAX_SEARCH_FLAC,"widened":widened,
                            "rawFiles":raw_files,"peerReplies":replies,"elapsedMs":started.elapsed().as_millis() as u64,
                            "peersAdmitted":soulseek_rs::types::PEERS_ADMITTED.load(Ordering::Relaxed) - admitted_before,
                            "peersRefused":soulseek_rs::types::PEERS_REFUSED.load(Ordering::Relaxed) - refused_before}));
                    }
                    // Do not remove a newer search registered under the same query.
                    let mut searches = lock(&runtime.searches);
                    if searches.get(&id).is_some_and(|f| Arc::ptr_eq(f, &cancelled)) {
                        searches.remove(&id);
                        for q in &active { let _ = client.forget_search(q); }
                    }
                });
            }
            "cancel_search" => { if let Some(flag) = lock(&self.searches).get(field(&value,"id")?) { flag.store(true, Ordering::Relaxed); } }
            "browse" => {
                let client = self.client()?;
                let id = field(&value,"id")?.to_string();
                let username = field(&value,"username")?.to_string();
                let directory = value["directory"].as_str().unwrap_or("").to_string();
                let wire_directory = directory.replace('/', "\\");
                if directory.is_empty() { client.browse_user(&username).map_err(|e| e.to_string())?; }
                else { client.request_folder_contents(&username, &wire_directory).map_err(|e| e.to_string())?; }
                let runtime = self.clone(); let generation = self.generation.load(Ordering::SeqCst);
                std::thread::spawn(move || {
                    let started = Instant::now();
                    while started.elapsed() < Duration::from_secs(30) && runtime.generation.load(Ordering::SeqCst) == generation {
                        let folders = if directory.is_empty() { client.take_browse_result(&username) }
                            else { client.take_folder_contents(&username, &wire_directory) };
                        if let Some(folders) = folders {
                            let mut files = Vec::new();
                            for folder in folders { for file in folder.files {
                                let path = format!("{}\\{}",folder.name.trim_end_matches('\\'),file.name);
                                if flac_path(&path) { files.push(file_json(&path,file.size,&file.attributes.into_iter().collect())); }
                            }}
                            runtime.push(json!({"type":"browse_result","id":id,"username":username,"directory":directory,"files":files})); return;
                        }
                        std::thread::sleep(Duration::from_millis(100));
                    }
                    if runtime.generation.load(Ordering::SeqCst) == generation {
                        runtime.push(json!({"type":"error","operation":"browse","id":id,"error":"The peer did not return a file listing"}));
                    }
                });
            }
            "download" => {
                let client = self.client()?; let id = field(&value,"id")?.to_string();
                let username = field(&value,"username")?.to_string(); let path = field(&value,"path")?.to_string();
                if !flac_path(&path) { return Err("Only FLAC files can be downloaded".into()); }
                let total = value["size"].as_u64().filter(|n| *n >= 42 && *n <= i64::MAX as u64).ok_or("Invalid download size")?;
                let directory = PathBuf::from(field(&value,"localDirectory")?);
                if !directory.is_absolute() { return Err("A private absolute staging directory is required".into()); }
                let mut transfers = lock(&self.transfers);
                if let Some(previous) = transfers.get(&id) {
                    if !previous.state.is_terminal() { return Err("This transfer is already active".into()); }
                    let _ = client.remove_download(&previous.username, &previous.path);
                    transfers.remove(&id);
                }
                if transfers.values().any(|t| t.username == username && t.path == path && !t.state.is_terminal()) { return Err("This file is already queued".into()); }
                std::fs::create_dir_all(&directory).map_err(|e|e.to_string())?;
                let (download, receiver) = client.download(path.clone(),username.clone(),total,directory.to_string_lossy().into_owned()).map_err(|e|e.to_string())?;
                transfers.insert(id, Transfer { username,path,total,directory,receiver,state:DownloadStatus::Queued,last:String::new(),verified:false,token:download.token });
            }
            "pause" | "resume" | "cancel" => {
                let client = self.client()?;
                let mut transfers = lock(&self.transfers);
                let id = field(&value,"id")?;
                let Some(tx) = transfers.get_mut(id) else {
                    // Finished and reported already: nothing left to pause, resume or cancel.
                    if lock(&self.retired).iter().any(|r| r == id) { return Ok(json!({"ok":true})); }
                    return Err("Unknown transfer".into());
                };
                let found = match op { "pause" => client.pause_download(&tx.username,&tx.path),
                    "resume" => client.resume_download(&tx.username,&tx.path),
                    _ => client.cancel_download(&tx.username,&tx.path) };
                if !found && !tx.state.is_terminal() { return Err("The transfer is no longer active".into()); }
            }
            "message" => {
                let client = self.client()?; let username = field(&value,"username")?;
                let text = field(&value,"text")?; let client_id = field(&value,"clientId")?;
                if !valid_username(username) || text.len() > 4096 { return Err("Invalid recipient or message length".into()); }
                client.send_private_message(username,text).map_err(|e|e.to_string())?;
                self.push(json!({"type":"message_sent","clientId":client_id,"username":username}));
            }
            "ack" => {
                let id = value["id"].as_u64().filter(|n| *n <= u32::MAX as u64).ok_or("Invalid message ID")?;
                self.client()?.acknowledge_private_message(id as u32).map_err(|e|e.to_string())?;
            }
            "contact" => {
                let username = field(&value,"username")?.to_string();
                if !valid_username(&username) { return Err("Invalid Soulseek username".into()); }
                self.client()?.watch_user(&username).map_err(|e|e.to_string())?;
                lock(&self.contacts).insert(username,None);
            }
            // How many peers may download from us at once (the host's upload limit).
            "upload_slots" => {
                let client = self.client()?;
                let slots = value["slots"].as_u64().filter(|n| (1..=10).contains(n)).ok_or("Uploads at once must be 1 to 10")?;
                client.set_upload_slots(slots as usize);
            }
            "shares" => {
                let client = self.client()?;
                let files = value["files"].as_array().ok_or("Missing shared files")?;
                if files.len() > 20_000 { return Err("The share folder contains too many files".into()); }
                // One file that fails a check (not FLAC by name or content, changed since the scan,
                // unreadable) is left out; it no longer takes the whole share down with it.
                let mut entries = Vec::new();
                let mut skipped = 0usize;
                for item in files {
                    let checked = (|| -> Result<HostSharedFile, String> {
                        let key = field(item,"key")?.to_string(); let path = field(item,"path")?.to_string();
                        if !flac_path(&path) { return Err("Only FLAC files may be shared".into()); }
                        let size = item["size"].as_u64().filter(|s|*s>=42).ok_or("Invalid shared file size")?;
                        // Verify the real selected document, not its provider MIME or extension.
                        let mut file = (self.open)(&key).map_err(|e|e.to_string())?;
                        let (rate,depth,duration) = check_flac(&mut file).map_err(|e|e.to_string())?;
                        if file.metadata().map_err(|e|e.to_string())?.len()!=size { return Err("The shared file changed; rescan the folder".into()); }
                        Ok(HostSharedFile{key,path,size,attributes:vec![(1,duration),(4,rate),(5,depth)]})
                    })();
                    match checked { Ok(entry) => entries.push(entry), Err(_) => skipped += 1 }
                }
                self.publish_shares(&client,entries.clone())?;
                let count=entries.len(); *lock(&self.share_entries)=entries;
                self.push(json!({"type":"shares","files":count,"skipped":skipped}));
            }
            _ => return Err(format!("Unknown native operation: {op}")),
        }
        Ok(json!({"ok":true}))
    }
    fn poll(&self) -> Value {
        let mut events: Vec<Value> = lock(&self.events).drain(..).collect();
        let Some(client) = lock(&self.client).clone() else { return json!(events); };
        if let Some(loss) = client.session_loss() {
            if !self.seen_loss.swap(true, Ordering::Relaxed) {
                events.push(json!({"type":"session","state":"disconnected","reasonCode":match loss {SessionLoss::Displaced=>"DISPLACED",_=>"NETWORK_LOST"},"reason":match loss {SessionLoss::Displaced=>"This Soulseek ID signed in on another device",_=>"The Soulseek connection was lost"}}));
            }
        }
        for message in client.take_private_messages() {
            events.push(json!({"type":"message","id":message.id(),"username":message.username(),"text":message.message(),"timestamp":message.timestamp(),"replayed":!message.is_new()}));
        }
        for (name, previous) in lock(&self.contacts).iter_mut() {
            if let Some(presence) = client.user_info(name).and_then(|info|info.presence) {
                let online = presence.status != soulseek_rs::UserStatus::Offline;
                if *previous != Some(online) { *previous=Some(online); events.push(json!({"type":"presence","username":name,"online":online})); }
            }
        }
        let mut transfers = lock(&self.transfers);
        // Copying the client's download list is only worth it while something is tracked.
        let downloads = if transfers.is_empty() { Vec::new() } else { client.get_all_downloads() };
        let mut finished = Vec::new();
        for (id, tx) in transfers.iter_mut() {
            while let Ok(update) = tx.receiver.try_recv() { tx.state=update; }
            let download = downloads.iter().find(|d| d.token==tx.token);
            if let Some(download) = download {
                // A rejected completed file must remain failed on subsequent polls.
                if !(tx.verified && matches!(tx.state, DownloadStatus::Failed(_))) {
                    tx.state=download.status.clone();
                }
            }
            let basename=tx.path.rsplit(['/', '\\']).next().unwrap_or("download.flac");
            let destination=tx.directory.join(basename);
            if matches!(tx.state,DownloadStatus::Completed) && !tx.verified {
                tx.verified=true;
                if File::open(&destination).and_then(|mut file|check_flac(&mut file)).is_err() {
                    let _=std::fs::remove_file(&destination);
                    tx.state=DownloadStatus::Failed(Some("The peer returned a file that is not valid native FLAC".into()));
                }
            }
            let (state,bytes,speed,error) = match &tx.state {
                DownloadStatus::Queued => ("queued",0,0.0,None),
                DownloadStatus::InProgress{bytes_downloaded,speed_bytes_per_sec,..}=>("downloading",*bytes_downloaded,*speed_bytes_per_sec,None),
                DownloadStatus::Paused{bytes_downloaded,..}=>("paused",*bytes_downloaded,0.0,None),
                DownloadStatus::Completed=>("completed",tx.total,0.0,None),
                DownloadStatus::Cancelled=>("cancelled",0,0.0,None),
                DownloadStatus::Failed(reason)=>("failed",0,0.0,reason.clone()),
                DownloadStatus::TimedOut=>("failed",0,0.0,Some("The peer timed out".into())),
            };
            let event=json!({"type":"transfer","id":id,"state":state,"bytes":bytes,"total":tx.total,"speed":speed,
                "queuePosition":download.and_then(|d|d.queue_position),"path":destination,"error":error});
            let signature=event.to_string();
            if signature!=tx.last { tx.last=signature;events.push(event); }
            // Its final state is in this batch: nothing more will change, so it stops costing
            // every later poll (and the client's own list) a copy and a comparison.
            if tx.state.is_terminal() { finished.push(id.clone()); }
        }
        for id in finished {
            if let Some(tx) = transfers.remove(&id) { let _ = client.remove_download(&tx.username, &tx.path); }
            let mut retired = lock(&self.retired);
            if retired.len() >= RETIRED_LIMIT { retired.pop_front(); }
            retired.push_back(id);
        }
        drop(transfers);
        let mut uploads=lock(&self.uploads);
        for upload in client.uploads() {
            let state=match upload.status { soulseek_rs::UploadStatus::Queued(_)=>"queued",soulseek_rs::UploadStatus::InProgress=>"uploading",soulseek_rs::UploadStatus::Completed=>"completed",soulseek_rs::UploadStatus::Cancelled=>"cancelled",_=>"failed" };
            let key=format!("{}\0{}",upload.username,upload.filename);
            let event=json!({"type":"upload","username":upload.username,"path":upload.filename,"state":state,"bytes":upload.bytes_sent,"total":upload.size});
            let signature=event.to_string();
            if uploads.get(&key)!=Some(&signature) { uploads.insert(key,signature);events.push(event); }
        }
        json!(events)
    }
}

fn file_json(path: &str, size: u64, attributes: &HashMap<u32,u32>) -> Value {
    let mut file=json!({"path":path,"size":size});
    for (code,key) in [(0,"bitrate"),(1,"durationSec"),(4,"sampleRate"),(5,"bitDepth")] {
        if let Some(value)=attributes.get(&code) { file[key]=json!(value); }
    }
    file
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static RUNTIMES: OnceLock<Mutex<HashMap<i64,Arc<Runtime>>>> = OnceLock::new();
fn runtimes() -> &'static Mutex<HashMap<i64,Arc<Runtime>>> { RUNTIMES.get_or_init(Mutex::default) }
fn runtime(handle:i64) -> Result<Arc<Runtime>,String> { lock(runtimes()).get(&handle).cloned().ok_or("Unknown native session".into()) }
fn java_string(env: &mut JNIEnv<'_>, value: String) -> jstring {
    env.new_string(value).map_or(std::ptr::null_mut(),|s|s.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_runout_soulseek_NativeSoulseek_nativeCreate(env:JNIEnv, owner:JObject) -> jlong {
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<i64,String> {
        let vm=env.get_java_vm().map_err(|e|e.to_string())?;
        let object:GlobalRef=env.new_global_ref(owner).map_err(|e|e.to_string())?;
        let open:OpenFile=Arc::new(move |key| {
            let mut env=vm.attach_current_thread().map_err(io::Error::other)?;
            let name=env.new_string(key).map_err(io::Error::other)?;
            let value=env.call_method(object.as_obj(),"openSharedFile","(Ljava/lang/String;)I",&[JValue::Object(&JObject::from(name))]);
            let fd=match value.and_then(|v|v.i()) { Ok(fd) if fd>=0=>fd,_=> {
                if env.exception_check().unwrap_or(false) { let _=env.exception_clear(); }
                return Err(io::Error::new(io::ErrorKind::PermissionDenied,"The shared document is unavailable"));
            }};
            // Ownership transfers from ParcelFileDescriptor.detachFd to Rust.
            Ok(unsafe { File::from_raw_fd(fd) })
        });
        let id=NEXT_HANDLE.fetch_add(1,Ordering::Relaxed);
        lock(runtimes()).insert(id,Runtime::new(open));Ok(id)
    }));
    result.ok().and_then(Result::ok).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_runout_soulseek_NativeSoulseek_nativeCommand(mut env:JNIEnv,_owner:JObject,handle:jlong,request:JString)->jstring {
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value,String> {
        let text:String=env.get_string(&request).map_err(|e|e.to_string())?.into();
        if text.len()>8*1024*1024 {return Err("Native command is too large".into());}
        let value=serde_json::from_str(&text).map_err(|_|"Invalid native command".to_string())?;
        runtime(handle)?.execute(value)
    }));
    let value=match result {Ok(Ok(v))=>v,Ok(Err(error))=>json!({"ok":false,"error":error}),Err(_)=>json!({"ok":false,"error":"The native operation failed"})};
    java_string(&mut env,value.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_runout_soulseek_NativeSoulseek_nativePoll(mut env:JNIEnv,_owner:JObject,handle:jlong)->jstring {
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||runtime(handle).map(|r|r.poll())));
    java_string(&mut env,match result {Ok(Ok(v))=>v.to_string(),_=>"[]".into()})
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_runout_soulseek_NativeSoulseek_nativeClose(_env:JNIEnv,_owner:JObject,handle:jlong) {
    let _=std::panic::catch_unwind(|| { if let Some(runtime)=lock(runtimes()).remove(&handle) {
        runtime.closed.store(true,Ordering::Relaxed);runtime.stop();
    }});
}

#[cfg(test)]
mod tests;
