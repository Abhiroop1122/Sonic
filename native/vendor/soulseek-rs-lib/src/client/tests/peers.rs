//! Uploads, broker connects, and the peers a disconnect may not drop.

use super::*;

#[test]
fn upload_speed_is_reported_only_while_running() {
    let two_seconds_ago = Instant::now()
        .checked_sub(Duration::from_secs(2))
        .expect("clock supports a 2s offset");

    // 1 KiB over ~2s is ~512 B/s.
    let rate = upload_speed(&UploadStatus::InProgress, 1024, two_seconds_ago);
    assert!((rate - 512.0).abs() < 50.0, "unexpected rate {rate}");

    // A finished upload reports no rate, exactly as a finished download
    // does, so the Speed column renders "-" rather than a stale figure.
    for status in [
        UploadStatus::Completed,
        UploadStatus::Cancelled,
        UploadStatus::Failed("nope".to_string()),
    ] {
        let rate = upload_speed(&status, 1024, two_seconds_ago);
        assert!(rate.abs() < f64::EPSILON, "unexpected rate {rate}");
    }

    // A just-started upload must not divide by a zero elapsed time.
    let rate = upload_speed(&UploadStatus::InProgress, 0, Instant::now());
    assert!(
        rate.is_finite() && rate.abs() < f64::EPSILON,
        "unexpected rate {rate}"
    );
}

// A clean disconnect — the idle reaper, or a remote client tidying an idle
// socket while it waits in our queue — must keep the peer's queued uploads;
// only an error disconnect is evidence the peer is gone.

#[test]
fn a_clean_disconnect_keeps_queued_uploads_an_error_drops_them() {
    let client = Client::new("test-user", "test-password");
    let (ops, ops_rx) = mpsc::channel();
    Client::listen_to_client_operations(
        ops_rx,
        client.context.clone(),
        "me".to_string(),
    );

    client.context.write().unwrap().enqueue_upload(
        "amy",
        "@@share\\f.mp3",
        std::path::PathBuf::from("/tmp/f.mp3"),
        4096,
    );

    ops.send(ClientOperation::PeerDisconnected(
        1,
        "amy".to_string(),
        None,
    ))
    .unwrap();
    // The loop is serial, so once this fence op is visible the disconnect
    // before it has been handled.
    ops.send(ClientOperation::OwnPrivileges(7)).unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    while client.own_privilege_seconds() != Some(7) {
        assert!(Instant::now() < deadline, "ops loop never caught up");
        thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(
        client
            .context
            .read()
            .unwrap()
            .place_in_queue("amy", "@@share\\f.mp3"),
        Some(1),
        "a clean disconnect must keep the queue"
    );

    ops.send(ClientOperation::PeerDisconnected(
        1,
        "amy".to_string(),
        Some(crate::error::SoulseekRs::NotConnected),
    ))
    .unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let place = client
            .context
            .read()
            .unwrap()
            .place_in_queue("amy", "@@share\\f.mp3");
        if place.is_none() {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "an error disconnect must drop the queue"
        );
        thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn a_pending_connect_expires_after_the_broker_timeout() {
    let mut context = ClientContext::new();
    context.add_pending_connect(7, "ghost".to_string());

    assert!(context.take_expired_connects(Instant::now()).is_empty());

    let after_deadline =
        Instant::now() + BROKER_CONNECT_TIMEOUT + Duration::from_secs(1);
    assert_eq!(
        context.take_expired_connects(after_deadline),
        vec!["ghost".to_string()]
    );
    assert!(context.take_pending_connect(7).is_none());
}

#[test]
fn protected_peers_covers_downloads_uploads_and_pending_serves() {
    let mut context = ClientContext::new();
    context.add_download(download(
        "downloader",
        "song.mp3",
        1,
        DownloadStatus::Queued,
        mpsc::channel().0,
    ));
    context.add_download(download(
        "done",
        "old.mp3",
        2,
        DownloadStatus::Completed,
        mpsc::channel().0,
    ));
    context
        .pending_serves
        .insert("waiting".to_string(), vec![9]);
    context.mark_browse_pending("browsed");

    let protected = context.protected_peers();
    assert!(protected.contains("downloader"));
    assert!(protected.contains("waiting"));
    assert!(protected.contains("browsed"));
    assert!(!protected.contains("done"));

    context.store_browse_result("browsed".to_string(), Vec::new());
    assert!(!context.protected_peers().contains("browsed"));
}

#[test]
fn a_peer_message_nobody_could_deliver_expires() {
    let mut ctx = ClientContext::for_user("me");
    ctx.queue_peer_message("ghost", crate::message::Message::new());
    ctx.expire_pending_peer_messages(Instant::now());
    assert_eq!(ctx.take_peer_messages("ghost").len(), 1, "still fresh");

    ctx.queue_peer_message("ghost", crate::message::Message::new());
    ctx.expire_pending_peer_messages(Instant::now() + PENDING_PEER_TTL);
    assert!(ctx.take_peer_messages("ghost").is_empty(), "expired");
}

#[test]
fn the_server_is_told_our_child_capacity_only_when_it_changes() {
    // AcceptChildren is a standing state on the server, not a heartbeat.
    let mut ctx = ClientContext::new();
    assert_eq!(
        ctx.accept_children_change(),
        Some(false),
        "the first answer is always worth sending"
    );
    assert_eq!(ctx.accept_children_change(), None, "unchanged, so silent");
}

// A relayed upload whose downloader never connects back frees its slot: the
// pierce is dropped and the upload reads as failed, not in progress forever.
#[test]
fn a_relayed_upload_nobody_answers_expires_as_failed() {
    let client = Client::new("test-user", "test-password");
    {
        let mut ctx = client.context.write().unwrap();
        ctx.active_uploads.insert(
            9,
            ActiveUpload {
                username: "amy".to_string(),
                filename: "@@share\\f.flac".to_string(),
                size: 4096,
                bytes_sent: Arc::new(std::sync::atomic::AtomicU64::new(0)),
                cancel: Arc::new(std::sync::atomic::AtomicBool::new(false)),
                status: UploadStatus::InProgress,
                started: Instant::now(),
            },
        );
        ctx.pending_upload_pierces.insert(
            77,
            PiercedUpload {
                token: 9,
                real_path: std::path::PathBuf::from("/tmp/f.flac"),
                deadline: Instant::now() + Duration::from_secs(30),
            },
        );
    }

    // Still inside the window: nothing changes.
    Client::expire_upload_relays(&client.context);
    assert!(client.context.read().unwrap().pending_upload_pierces.contains_key(&77));

    client.context.write().unwrap().pending_upload_pierces.get_mut(&77).unwrap().deadline =
        Instant::now().checked_sub(Duration::from_secs(1)).unwrap();
    Client::expire_upload_relays(&client.context);
    let ctx = client.context.read().unwrap();
    assert!(ctx.pending_upload_pierces.is_empty());
    assert!(matches!(ctx.active_uploads[&9].status, UploadStatus::Failed(_)));
    // The pierce is consumed once: a late PierceFirewall finds nothing.
    drop(ctx);
    assert!(client.context.write().unwrap().take_upload_pierce(77).is_none());
}

// A browse of someone the server gives no address for ends as "offline" at
// once, and the next browse starts with a clean slate.
#[test]
fn an_addressless_peer_reads_as_offline_until_the_next_browse() {
    let client = Client::new("test-user", "test-password");
    let (ops, ops_rx) = mpsc::channel();
    Client::listen_to_client_operations(ops_rx, client.context.clone(), "me".to_string());

    ops.send(ClientOperation::GetPeerAddressResponse {
        username: "amy".to_string(),
        host: "0.0.0.0".to_string(),
        port: 0,
        obfuscation_type: 0,
        obfuscated_port: 0,
    })
    .unwrap();
    ops.send(ClientOperation::OwnPrivileges(7)).unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    while client.own_privilege_seconds() != Some(7) {
        assert!(Instant::now() < deadline, "ops loop never caught up");
        thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(client.peer_reach("amy"), Some(crate::types::PeerReach::Offline));
    // A relay failure after that says nothing new.
    client.context.write().unwrap().set_peer_reach("amy", crate::types::PeerReach::Unreachable);
    assert_eq!(client.peer_reach("amy"), Some(crate::types::PeerReach::Offline));

    client.context.write().unwrap().mark_browse_pending("amy");
    assert_eq!(client.peer_reach("amy"), None);
    client.context.write().unwrap().set_peer_reach("amy", crate::types::PeerReach::Unreachable);
    assert_eq!(client.peer_reach("amy"), Some(crate::types::PeerReach::Unreachable));
}
