//! End-to-end tests: real clients against a real server on a random port.

use std::{
    sync::{Arc, Mutex},
    time::Duration,
};

use messenger_core::{Client, ConversationKind, Error, LocationFix, MessageStatus, PushTarget};
use messenger_server::push::{Delivery, PushConfig};
use tempfile::TempDir;

struct TestServer {
    url: String,
    _runtime: tokio::runtime::Runtime,
    _dir: TempDir,
}

fn start_server() -> TestServer {
    start_server_with(PushConfig::default())
}

fn start_server_with(push: PushConfig) -> TestServer {
    let dir = tempfile::tempdir().unwrap();
    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
    let config =
        messenger_server::Config { db_path: dir.path().join("server.db"), retention: Duration::from_secs(3600), push };
    let listener = runtime.block_on(tokio::net::TcpListener::bind("127.0.0.1:0")).unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    runtime.spawn(messenger_server::serve(listener, config, std::future::pending()));
    TestServer { url, _runtime: runtime, _dir: dir }
}

struct Devices {
    server: TestServer,
    dir: TempDir,
}

impl Devices {
    fn new() -> Self {
        Self { server: start_server(), dir: tempfile::tempdir().unwrap() }
    }

    fn with_push(push: PushConfig) -> Self {
        Self { server: start_server_with(push), dir: tempfile::tempdir().unwrap() }
    }

    fn open(&self, name: &str) -> Client {
        Client::open(self.dir.path().join(name), &self.server.url).unwrap()
    }

    fn register(&self, name: &str) -> Client {
        let client = self.open(name);
        client.register(name).unwrap();
        client
    }
}

/// Syncs every client a few times so all queued work and replies settle.
fn settle(clients: &[&Client]) {
    for _ in 0..4 {
        for client in clients {
            client.sync(0).unwrap();
        }
    }
}

fn texts(client: &Client, conversation: &str) -> Vec<String> {
    client
        .messages(conversation, 500)
        .unwrap()
        .into_iter()
        .filter(|m| !m.system)
        .map(|m| if m.deleted { "<deleted>".to_string() } else { m.text })
        .collect()
}

fn only_conversation(client: &Client) -> String {
    let conversations = client.conversations().unwrap();
    assert_eq!(conversations.len(), 1, "expected exactly one conversation");
    conversations[0].id.clone()
}

fn member_names(client: &Client, conversation: &str) -> Vec<String> {
    let mut names: Vec<String> = client.members(conversation).unwrap().into_iter().map(|m| m.username).collect();
    names.sort();
    names
}

#[test]
fn direct_chat_round_trip() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");

    let chat = alice.start_direct_chat("bob").unwrap();
    let hello = alice.send_text(&chat, "hi bob", None).unwrap();
    let report = bob.sync(0).unwrap();
    assert_eq!(report.joined_conversations, vec![chat.clone()]);
    assert_eq!(texts(&bob, &chat), vec!["hi bob"]);
    let summary = bob.conversation(&chat).unwrap();
    assert_eq!(summary.kind, ConversationKind::Direct);
    assert_eq!(summary.title, "alice");

    // Reply, react, edit, delete.
    let reply = bob.send_text(&chat, "hey alice", Some(&hello)).unwrap();
    bob.react(&chat, &hello, Some("👍")).unwrap();
    settle(&[&alice, &bob]);
    let alice_view = alice.messages(&chat, 50).unwrap();
    let hello_view = alice_view.iter().find(|m| m.id == hello).unwrap();
    assert_eq!(hello_view.status, MessageStatus::Sent);
    assert_eq!(hello_view.reactions.len(), 1);
    assert_eq!(hello_view.reactions[0].emoji, "👍");
    assert_eq!(hello_view.reactions[0].usernames, vec!["bob"]);
    let reply_view = alice_view.iter().find(|m| m.id == reply).unwrap();
    assert_eq!(reply_view.reply_to.as_ref().unwrap().text, "hi bob");

    alice.edit_message(&chat, &hello, "hi bob!").unwrap();
    bob.delete_message(&chat, &reply).unwrap();
    // Nobody can edit or delete someone else's message.
    assert!(matches!(bob.edit_message(&chat, &hello, "hacked"), Err(Error::InvalidInput(_))));
    settle(&[&alice, &bob]);
    assert_eq!(texts(&alice, &chat), vec!["hi bob!", "<deleted>"]);
    assert_eq!(texts(&bob, &chat), vec!["hi bob!", "<deleted>"]);
    assert!(bob.messages(&chat, 50).unwrap().iter().find(|m| m.id == hello).unwrap().edited);

    // Both sides compute the same safety number.
    let a = alice.safety_number(&chat).unwrap();
    let b = bob.safety_number(&chat).unwrap();
    assert_eq!(a.digits, b.digits);
    assert_eq!(a.qr_payload.len(), 60);

    // Starting the chat again returns the same conversation.
    assert_eq!(alice.start_direct_chat("bob").unwrap(), chat);
}

#[test]
fn group_add_and_remove() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    let dave = net.register("dave");

    let group = alice.create_group("Friends", &["bob".into(), "carol".into()]).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(member_names(&carol, &group), vec!["alice", "bob", "carol"]);
    alice.send_text(&group, "from alice", None).unwrap();
    bob.send_text(&group, "from bob", None).unwrap();
    carol.send_text(&group, "from carol", None).unwrap();
    settle(&[&alice, &bob, &carol]);
    for client in [&alice, &bob, &carol] {
        let mut got = texts(client, &group);
        got.sort();
        assert_eq!(got, vec!["from alice", "from bob", "from carol"]);
    }

    // Dave joins later and sees only what's sent after.
    bob.add_members(&group, &["dave".into()]).unwrap();
    settle(&[&alice, &bob, &carol, &dave]);
    assert_eq!(member_names(&alice, &group), vec!["alice", "bob", "carol", "dave"]);
    dave.send_text(&group, "hi all", None).unwrap();
    settle(&[&alice, &bob, &carol, &dave]);
    assert_eq!(texts(&dave, &group), vec!["hi all"]);
    assert!(texts(&carol, &group).contains(&"hi all".to_string()));

    // Carol is removed and can't read what follows.
    alice.remove_member(&group, "carol").unwrap();
    settle(&[&alice, &bob, &carol, &dave]);
    assert!(!carol.conversation(&group).unwrap().active);
    alice.send_text(&group, "after carol", None).unwrap();
    settle(&[&alice, &bob, &carol, &dave]);
    assert!(!texts(&carol, &group).contains(&"after carol".to_string()));
    assert!(texts(&bob, &group).contains(&"after carol".to_string()));
    assert!(texts(&dave, &group).contains(&"after carol".to_string()));
    assert_eq!(member_names(&dave, &group), vec!["alice", "bob", "dave"]);
    assert!(matches!(carol.send_text(&group, "still here?", None), Err(Error::NotAMember)));
    // Membership notices are shown.
    let notices: Vec<String> =
        bob.messages(&group, 100).unwrap().into_iter().filter(|m| m.system).map(|m| m.text).collect();
    assert!(notices.iter().any(|t| t == "alice removed carol"), "{notices:?}");
}

#[test]
fn concurrent_commits_converge() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    let dave = net.register("dave");

    let group = alice.create_group("Race", &["bob".into()]).unwrap();
    settle(&[&alice, &bob]);
    // Bob queues messages and a change without seeing Alice's change first:
    // his commit and messages lose the race and must be redone in the next epoch.
    bob.send_text(&group, "bob before", None).unwrap();
    alice.add_members(&group, &["carol".into()]).unwrap();
    bob.add_members(&group, &["dave".into()]).unwrap();
    bob.send_text(&group, "bob after", None).unwrap();
    settle(&[&alice, &bob, &carol, &dave]);

    for client in [&alice, &bob, &carol, &dave] {
        assert_eq!(member_names(client, &group), vec!["alice", "bob", "carol", "dave"]);
    }
    let alice_texts = texts(&alice, &group);
    assert!(alice_texts.contains(&"bob before".to_string()), "{alice_texts:?}");
    assert!(alice_texts.contains(&"bob after".to_string()), "{alice_texts:?}");
    // Everyone ends in the same epoch, so a new message reaches everyone.
    carol.send_text(&group, "all here", None).unwrap();
    settle(&[&alice, &bob, &carol, &dave]);
    for client in [&alice, &bob, &dave] {
        assert!(texts(client, &group).contains(&"all here".to_string()));
    }
}

/// Crashes at each point of a commit's life, restarts the client from disk,
/// and checks the group still forms and works.
#[test]
fn crash_mid_commit_recovers() {
    for point in ["commit-created", "commit-accepted", "commit-merge"] {
        let net = Devices::new();
        let alice = net.register("alice");
        let bob = net.register("bob");

        alice.inject_fault(Some(point));
        let chat = alice.start_direct_chat("bob").unwrap();
        // The fault fired: sync keeps failing at the same point.
        assert!(matches!(alice.sync(0), Err(Error::InjectedFault(p)) if p == point), "{point}");
        drop(alice);

        // Restart from the same directory.
        let alice = net.open("alice");
        settle(&[&alice, &bob]);
        assert_eq!(only_conversation(&bob), chat, "{point}");
        alice.send_text(&chat, "after the crash", None).unwrap();
        bob.send_text(&chat, "got it", None).unwrap();
        settle(&[&alice, &bob]);
        assert_eq!(texts(&bob, &chat), vec!["after the crash", "got it"], "{point}");
        assert_eq!(texts(&alice, &chat), vec!["after the crash", "got it"], "{point}");
    }
}

#[test]
fn messages_queue_while_a_commit_is_in_flight() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    alice.inject_fault(Some("commit-created"));
    let chat = alice.start_direct_chat("bob").unwrap();
    // Restart: the commit that invites bob is saved but was never posted. A
    // message sent now waits until that commit has gone through.
    drop(alice);
    let alice = net.open("alice");
    alice.send_text(&chat, "queued", None).unwrap();
    settle(&[&alice, &bob]);
    assert_eq!(texts(&bob, &chat), vec!["queued"]);
    assert_eq!(alice.messages(&chat, 10).unwrap().last().unwrap().status, MessageStatus::Sent);
}

#[test]
fn recovery_moves_account_to_new_device() {
    let net = Devices::new();
    let old = net.open("alice-old");
    let registration = old.register("alice").unwrap();
    assert_eq!(old.pending_recovery_key().unwrap().as_deref(), Some(registration.recovery_key.as_str()));
    old.confirm_recovery_key_saved().unwrap();
    assert_eq!(old.pending_recovery_key().unwrap(), None);
    let bob = net.register("bob");

    let fresh = net.open("alice-new");
    assert!(matches!(fresh.recover("alice", "aaaa bbbb"), Err(Error::BadRecoveryKey)));
    let wrong = "abcd ".repeat(16);
    assert!(matches!(fresh.recover("alice", &wrong), Err(Error::BadRecoveryKey)));
    let account = fresh.recover("alice", &registration.recovery_key).unwrap();
    assert_eq!(account.account_id, registration.account.account_id);

    let chat = bob.start_direct_chat("alice").unwrap();
    bob.send_text(&chat, "new phone?", None).unwrap();
    settle(&[&fresh, &bob]);
    assert_eq!(texts(&fresh, &chat), vec!["new phone?"]);
}

#[test]
fn registration_rules() {
    let net = Devices::new();
    let alice = net.register("alice");
    assert!(matches!(alice.register("alice2"), Err(Error::AlreadyRegistered(_))));
    let other = net.open("other");
    assert!(matches!(other.register("ALICE"), Err(Error::UsernameTaken)));
    assert!(matches!(other.register("x"), Err(Error::InvalidInput(_))));
    let other = net.register("other");
    assert!(matches!(other.start_direct_chat("nobody"), Err(Error::UserNotFound(_))));
    assert!(matches!(other.start_direct_chat("other"), Err(Error::InvalidInput(_))));
}

#[test]
fn key_packages_are_replenished() {
    let net = Devices::new();
    let alice = net.register("alice");
    // More invitations than the 20 uploaded key packages: the last-resort
    // package keeps alice reachable, and her syncs top the supply back up.
    let mut friends = Vec::new();
    for i in 0..22 {
        let friend = net.register(&format!("friend{i}"));
        friend.start_direct_chat("alice").unwrap();
        friends.push(friend);
    }
    settle(&[&alice]);
    assert_eq!(alice.conversations().unwrap().len(), 22);
}

fn notices(client: &Client, conversation: &str) -> Vec<String> {
    client.messages(conversation, 500).unwrap().into_iter().filter(|m| m.system).map(|m| m.text).collect()
}

#[test]
fn leaving_a_group() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");

    let group = alice.create_group("Trip", &["bob".into(), "carol".into()]).unwrap();
    settle(&[&alice, &bob, &carol]);
    carol.leave_conversation(&group).unwrap();
    // Until someone processes it, carol shows as leaving and can't post.
    assert!(carol.conversation(&group).unwrap().leaving);
    assert!(matches!(carol.send_text(&group, "bye?", None), Err(Error::NotAMember)));
    settle(&[&alice, &bob, &carol]);

    let summary = carol.conversation(&group).unwrap();
    assert!(!summary.active && !summary.leaving);
    assert!(notices(&carol, &group).contains(&"You left".to_string()), "{:?}", notices(&carol, &group));
    for client in [&alice, &bob] {
        assert_eq!(member_names(client, &group), vec!["alice", "bob"]);
        assert!(notices(client, &group).contains(&"carol left".to_string()), "{:?}", notices(client, &group));
    }
    // The group carries on without her.
    bob.send_text(&group, "just us", None).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(texts(&alice, &group), vec!["just us"]);
    assert!(texts(&carol, &group).is_empty());
}

#[test]
fn leaving_races_with_other_changes() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    let dave = net.register("dave");

    let group = alice.create_group("Busy", &["bob".into(), "carol".into()]).unwrap();
    settle(&[&alice, &bob, &carol]);
    // Bob and carol leave while alice adds dave, none of them having seen the
    // others: whichever lands first, everyone ends up agreeing.
    bob.leave_conversation(&group).unwrap();
    carol.leave_conversation(&group).unwrap();
    alice.add_members(&group, &["dave".into()]).unwrap();
    for _ in 0..3 {
        settle(&[&alice, &bob, &carol, &dave]);
    }
    assert_eq!(member_names(&alice, &group), vec!["alice", "dave"]);
    assert_eq!(member_names(&dave, &group), vec!["alice", "dave"]);
    assert!(!bob.conversation(&group).unwrap().active);
    assert!(!carol.conversation(&group).unwrap().active);
    dave.send_text(&group, "hello alice", None).unwrap();
    settle(&[&alice, &dave]);
    assert_eq!(texts(&alice, &group), vec!["hello alice"]);
}

#[test]
fn leaving_a_direct_chat_ends_it() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let chat = alice.start_direct_chat("bob").unwrap();
    alice.send_text(&chat, "hi", None).unwrap();
    settle(&[&alice, &bob]);
    bob.leave_conversation(&chat).unwrap();
    settle(&[&alice, &bob]);
    let summary = alice.conversation(&chat).unwrap();
    assert!(!summary.active);
    assert!(notices(&alice, &chat).contains(&"bob left".to_string()), "{:?}", notices(&alice, &chat));
    // Starting a chat with bob again makes a new one.
    let again = alice.start_direct_chat("bob").unwrap();
    assert_ne!(again, chat);
    alice.send_text(&again, "you there?", None).unwrap();
    settle(&[&alice, &bob]);
    assert_eq!(texts(&bob, &again), vec!["you there?"]);
}

#[test]
fn deleting_an_account() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    let chat = alice.start_direct_chat("bob").unwrap();
    let group = alice.create_group("Three", &["bob".into(), "carol".into()]).unwrap();
    settle(&[&alice, &bob, &carol]);

    bob.delete_account().unwrap();
    // Bob's device is empty, like a fresh install.
    assert!(bob.account().unwrap().is_none());
    assert!(bob.conversations().unwrap().is_empty());
    settle(&[&alice, &carol]);
    assert!(!alice.conversation(&chat).unwrap().active);
    assert!(notices(&alice, &chat).contains(&"bob left".to_string()));
    assert_eq!(member_names(&carol, &group), vec!["alice", "carol"]);
    assert!(matches!(alice.start_direct_chat("bob"), Err(Error::UserNotFound(_))));

    // The username is free again, and the same device can sign up anew.
    bob.register("bob").unwrap();
    let fresh = alice.start_direct_chat("bob").unwrap();
    alice.send_text(&fresh, "new bob?", None).unwrap();
    settle(&[&alice, &bob]);
    assert_eq!(texts(&bob, &fresh), vec!["new bob?"]);
}

#[test]
fn removing_the_account_from_a_device() {
    let net = Devices::new();
    let phone = net.open("alice-phone");
    let registration = phone.register("alice").unwrap();
    let bob = net.register("bob");
    let chat = bob.start_direct_chat("alice").unwrap();
    settle(&[&phone, &bob]);

    phone.remove_from_device().unwrap();
    assert!(phone.account().unwrap().is_none());
    assert!(phone.conversations().unwrap().is_empty());
    settle(&[&bob]);
    assert!(!bob.conversation(&chat).unwrap().active);
    // With no device, nobody can start a chat with alice...
    let carol = net.register("carol");
    assert!(matches!(carol.start_direct_chat("alice"), Err(Error::NoKeyPackages(_))));
    // ...until the recovery key brings the account back.
    let laptop = net.open("alice-laptop");
    laptop.recover("alice", &registration.recovery_key).unwrap();
    let chat = carol.start_direct_chat("alice").unwrap();
    carol.send_text(&chat, "welcome back", None).unwrap();
    settle(&[&laptop, &carol]);
    assert_eq!(texts(&laptop, &chat), vec!["welcome back"]);
}

#[test]
fn wiping_this_device() {
    let net = Devices::new();
    let phone = net.open("alice-phone");
    let registration = phone.register("alice").unwrap();
    phone.confirm_recovery_key_saved().unwrap();
    let bob = net.register("bob");
    let chat = bob.start_direct_chat("alice").unwrap();
    bob.send_text(&chat, "hi alice", None).unwrap();
    settle(&[&phone, &bob]);
    assert_eq!(texts(&phone, &chat), vec!["hi alice"]);

    phone.wipe_this_device().unwrap();
    assert!(phone.account().unwrap().is_none());
    assert!(phone.conversations().unwrap().is_empty());
    assert!(phone.pending_recovery_key().unwrap().is_none());
    // Nothing went out: for bob the chat carries on as before.
    settle(&[&bob]);
    assert!(bob.conversation(&chat).unwrap().active);
    assert_eq!(texts(&bob, &chat), vec!["hi alice"]);

    // Reopened, the device is as empty as a fresh install...
    drop(phone);
    let phone = net.open("alice-phone");
    assert!(phone.account().unwrap().is_none());
    assert!(phone.conversations().unwrap().is_empty());
    // ...and the recovery key brings the account back, without the old chats.
    phone.recover("alice", &registration.recovery_key).unwrap();
    assert!(phone.conversations().unwrap().is_empty());
    let carol = net.register("carol");
    let fresh = carol.start_direct_chat("alice").unwrap();
    carol.send_text(&fresh, "still here?", None).unwrap();
    settle(&[&phone, &carol]);
    assert_eq!(texts(&phone, &fresh), vec!["still here?"]);
}

#[test]
fn replacing_the_recovery_key() {
    let net = Devices::new();
    let alice = net.open("alice");
    let registration = alice.register("alice").unwrap();
    alice.confirm_recovery_key_saved().unwrap();

    let new_key = alice.new_recovery_key().unwrap();
    assert_ne!(new_key, registration.recovery_key);
    assert_eq!(alice.pending_recovery_key().unwrap().as_deref(), Some(new_key.as_str()));
    alice.confirm_recovery_key_saved().unwrap();

    let other = net.open("other");
    assert!(matches!(other.recover("alice", &registration.recovery_key), Err(Error::BadRecoveryKey)));
    other.recover("alice", &new_key).unwrap();
}

#[test]
fn read_receipts_both_ways() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let chat = alice.start_direct_chat("bob").unwrap();
    let hello = alice.send_text(&chat, "hello", None).unwrap();
    settle(&[&alice, &bob]);
    let status =
        |client: &Client, id: &str| client.messages(&chat, 50).unwrap().into_iter().find(|m| m.id == id).unwrap();
    assert_eq!(status(&alice, &hello).status, MessageStatus::Sent);

    bob.mark_read(&chat).unwrap();
    settle(&[&alice, &bob]);
    let view = status(&alice, &hello);
    assert_eq!(view.status, MessageStatus::Read);
    assert_eq!(view.read_by, vec!["bob"]);

    // Bob turns receipts off: his reads aren't reported, and alice's aren't shown to him.
    let mut settings = bob.privacy_settings().unwrap();
    settings.read_receipts = false;
    bob.set_privacy_settings(settings).unwrap();
    let second = alice.send_text(&chat, "second", None).unwrap();
    let reply = bob.send_text(&chat, "reply", None).unwrap();
    settle(&[&alice, &bob]);
    bob.mark_read(&chat).unwrap();
    alice.mark_read(&chat).unwrap();
    settle(&[&alice, &bob]);
    assert_eq!(status(&alice, &second).status, MessageStatus::Sent);
    assert_eq!(status(&bob, &reply).status, MessageStatus::Sent);
    // Turning them back on doesn't report what was read while off.
    settings.read_receipts = true;
    bob.set_privacy_settings(settings).unwrap();
    bob.mark_read(&chat).unwrap();
    settle(&[&alice, &bob]);
    assert_eq!(status(&alice, &second).status, MessageStatus::Sent);
}

#[test]
fn read_ticks_count_the_people_a_message_went_to() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    let group = alice.create_group("plans", &["bob".to_string()]).unwrap();
    settle(&[&alice, &bob]);
    let status = |id: &str| alice.messages(&group, 50).unwrap().into_iter().find(|m| m.id == id).unwrap().status;

    let early = alice.send_text(&group, "before carol", None).unwrap();
    settle(&[&alice, &bob]);
    bob.mark_read(&group).unwrap();
    settle(&[&alice, &bob]);
    assert_eq!(status(&early), MessageStatus::Read);

    // Carol joins later: she never got the early message, so it stays read.
    alice.add_members(&group, &["carol".to_string()]).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(status(&early), MessageStatus::Read);
    let late = alice.send_text(&group, "with carol", None).unwrap();
    settle(&[&alice, &bob, &carol]);
    bob.mark_read(&group).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(status(&late), MessageStatus::Sent);
    carol.mark_read(&group).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(status(&late), MessageStatus::Read);

    // Bob leaving doesn't take anything back.
    bob.leave_conversation(&group).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!((status(&early), status(&late)), (MessageStatus::Read, MessageStatus::Read));
}

#[test]
fn read_ticks_stay_when_a_one_to_one_chat_ends() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let chat = alice.start_direct_chat("bob").unwrap();
    let hello = alice.send_text(&chat, "hello", None).unwrap();
    settle(&[&alice, &bob]);
    // A new chat counts as unread for its message, not for being started.
    assert_eq!(bob.conversation(&chat).unwrap().unread, 1);
    bob.mark_read(&chat).unwrap();
    settle(&[&alice, &bob]);
    bob.leave_conversation(&chat).unwrap();
    settle(&[&alice, &bob]);
    assert!(!alice.conversation(&chat).unwrap().active);
    let view = alice.messages(&chat, 50).unwrap().into_iter().find(|m| m.id == hello).unwrap();
    assert_eq!(view.status, MessageStatus::Read);
}

#[test]
fn typing_indicators() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let chat = alice.start_direct_chat("bob").unwrap();
    settle(&[&alice, &bob]);

    alice.set_typing(&chat, true).unwrap();
    let report = bob.sync(0).unwrap();
    assert_eq!(report.typing_changed, vec![chat.clone()]);
    assert_eq!(bob.typing_users(&chat).unwrap(), vec!["alice"]);
    // Sending the message clears it.
    alice.send_text(&chat, "done typing", None).unwrap();
    bob.sync(0).unwrap();
    assert!(bob.typing_users(&chat).unwrap().is_empty());

    // Off on bob's side: he neither shows nor sends them.
    bob.set_privacy_settings(messenger_core::PrivacySettings { read_receipts: true, typing_indicators: false })
        .unwrap();
    alice.set_typing(&chat, true).unwrap();
    bob.set_typing(&chat, true).unwrap();
    settle(&[&alice, &bob]);
    assert!(bob.typing_users(&chat).unwrap().is_empty());
    assert!(alice.typing_users(&chat).unwrap().is_empty());
    // Typing indicators are never stored as messages.
    assert_eq!(texts(&bob, &chat), vec!["done typing"]);
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64
}

fn fix(latitude: f64, longitude: f64) -> LocationFix {
    LocationFix {
        latitude,
        longitude,
        accuracy_m: 12.0,
        measured_at_ms: now_ms(),
        speed_mps: Some(1.4),
        course_deg: Some(90.0),
        battery_percent: Some(80),
    }
}

#[test]
fn sharing_location() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let chat = alice.start_direct_chat("bob").unwrap();
    alice.send_text(&chat, "hi", None).unwrap();
    settle(&[&alice, &bob]);
    let unread = bob.conversation(&chat).unwrap().unread;

    // Nothing goes out before alice starts sharing.
    assert_eq!(alice.update_location(fix(18.52, 73.85)).unwrap(), 0);
    alice.start_sharing_location(&chat, None).unwrap();
    assert_eq!(alice.location_shares().unwrap().len(), 1);
    assert_eq!(alice.update_location(fix(18.52, 73.85)).unwrap(), 1);
    let report = bob.sync(0).unwrap();
    assert_eq!(report.locations_changed, vec![chat.clone()]);
    let seen = bob.shared_locations().unwrap();
    assert_eq!(seen.len(), 1);
    assert_eq!(seen[0].username, "alice");
    assert_eq!((seen[0].latitude, seen[0].longitude), (18.52, 73.85));
    assert_eq!(seen[0].battery_percent, Some(80));
    assert_eq!(seen[0].until_ms, None);
    // Positions never show up as messages or unread chats.
    assert_eq!(texts(&bob, &chat), vec!["hi"]);
    assert_eq!(bob.conversation(&chat).unwrap().unread, unread);
    // Alice doesn't see her own location, and bob shares nothing.
    assert!(alice.shared_locations().unwrap().is_empty());

    // A newer position replaces the old one; updates closer than 2 s apart are skipped.
    assert_eq!(alice.update_location(fix(18.53, 73.86)).unwrap(), 0);
    std::thread::sleep(Duration::from_millis(2100));
    assert_eq!(alice.update_location(fix(18.53, 73.86)).unwrap(), 1);
    bob.sync(0).unwrap();
    let seen = bob.shared_locations().unwrap();
    assert_eq!((seen[0].latitude, seen[0].longitude), (18.53, 73.86));

    // Stopping makes bob forget it at once.
    alice.stop_sharing_location(&chat).unwrap();
    assert!(alice.location_shares().unwrap().is_empty());
    let report = bob.sync(0).unwrap();
    assert_eq!(report.locations_changed, vec![chat.clone()]);
    assert!(bob.shared_locations().unwrap().is_empty());
}

#[test]
fn only_the_latest_location_goes_out() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    let group = alice.create_group("family", &["bob".to_string(), "carol".to_string()]).unwrap();
    settle(&[&alice, &bob, &carol]);

    alice.start_sharing_location(&group, Some(now_ms() + 3_600_000)).unwrap();
    // Bob adds someone while alice's updates wait: her first ones lose the race
    // for the epoch, and only the newest is sent again.
    let dave = net.register("dave");
    bob.add_members(&group, &["dave".to_string()]).unwrap();
    for (i, lat) in [10.0, 11.0, 12.0].into_iter().enumerate() {
        if i > 0 {
            std::thread::sleep(Duration::from_millis(2100));
        }
        alice.update_location(fix(lat, 20.0)).unwrap();
    }
    settle(&[&alice, &bob, &carol, &dave]);
    for viewer in [&bob, &carol, &dave] {
        let seen = viewer.shared_locations().unwrap();
        assert_eq!(seen.len(), 1);
        assert_eq!((seen[0].username.as_str(), seen[0].latitude), ("alice", 12.0));
        assert!(seen[0].until_ms.is_some());
    }
}

#[test]
fn shared_locations_expire_and_leave_with_their_owner() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    let group = alice.create_group("trip", &["bob".to_string(), "carol".to_string()]).unwrap();
    settle(&[&alice, &bob, &carol]);

    // Bob shares briefly: once the time is up, nobody shows it any more.
    bob.start_sharing_location(&group, Some(now_ms() + 1_500)).unwrap();
    assert_eq!(bob.update_location(fix(1.0, 2.0)).unwrap(), 1);
    // Carol shares until she stops.
    carol.start_sharing_location(&group, None).unwrap();
    carol.update_location(fix(3.0, 4.0)).unwrap();
    settle(&[&alice, &bob, &carol]);
    let names = |client: &Client| {
        let mut names: Vec<String> = client.shared_locations().unwrap().into_iter().map(|l| l.username).collect();
        names.sort();
        names
    };
    assert_eq!(names(&alice), vec!["bob", "carol"]);
    std::thread::sleep(Duration::from_millis(1600));
    assert_eq!(names(&alice), vec!["carol"]);
    assert!(bob.location_shares().unwrap().is_empty());
    assert_eq!(bob.update_location(fix(1.0, 2.0)).unwrap(), 0);

    // Carol leaves the group: her location goes with her.
    carol.leave_conversation(&group).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert!(names(&alice).is_empty());
    assert!(carol.location_shares().unwrap().is_empty());
}

#[test]
fn bad_positions_are_refused() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let chat = alice.start_direct_chat("bob").unwrap();
    settle(&[&alice, &bob]);
    alice.start_sharing_location(&chat, None).unwrap();
    for bad in [fix(91.0, 0.0), fix(0.0, 181.0), fix(f64::NAN, 0.0), LocationFix { accuracy_m: -1.0, ..fix(0.0, 0.0) }]
    {
        assert!(matches!(alice.update_location(bad), Err(Error::InvalidInput(_))));
    }
    assert!(matches!(alice.start_sharing_location(&chat, Some(now_ms() - 1)), Err(Error::InvalidInput(_))));
    // A stale position (from long ago) isn't worth sending.
    let old = LocationFix { measured_at_ms: now_ms() - 3_600_000, ..fix(1.0, 1.0) };
    assert_eq!(alice.update_location(old).unwrap(), 0);
}

// ---- push notifications ----

/// Pushes the server would have sent, by username.
#[derive(Clone, Default)]
struct Pushes(Arc<Mutex<Vec<String>>>);

impl Pushes {
    fn config(&self) -> PushConfig {
        let log = self.0.clone();
        PushConfig {
            min_gap: Duration::ZERO,
            recorder: Some(Arc::new(move |delivery: Delivery| log.lock().unwrap().push(delivery.username))),
            ..PushConfig::default()
        }
    }

    /// Waits for the server's push queue to drain, then returns and clears
    /// what was pushed, sorted.
    fn take(&self) -> Vec<String> {
        std::thread::sleep(Duration::from_millis(600));
        let mut pushed = std::mem::take(&mut *self.0.lock().unwrap());
        pushed.sort();
        pushed
    }
}

fn apns(byte: u8) -> PushTarget {
    PushTarget::Apns { token: vec![byte; 32], sandbox: true }
}

#[test]
fn pushes_wake_only_the_people_a_message_is_for() {
    let pushes = Pushes::default();
    let net = Devices::with_push(pushes.config());
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    for (client, byte) in [(&alice, 1), (&bob, 2), (&carol, 3)] {
        client.set_push_target(apns(byte)).unwrap();
    }
    assert_eq!(alice.push_target().unwrap(), Some(apns(1)));
    assert!(alice.push_info().unwrap().vapid_public_key.len() == 65);

    // The invitation wakes bob.
    let chat = alice.start_direct_chat("bob").unwrap();
    assert_eq!(pushes.take(), vec!["bob"]);
    settle(&[&alice, &bob]);
    pushes.take();

    // Messages wake the others, not the sender.
    alice.send_text(&chat, "hi bob", None).unwrap();
    assert_eq!(pushes.take(), vec!["bob"]);
    bob.sync(0).unwrap();
    let chat_for_bob = only_conversation(&bob);
    bob.send_text(&chat_for_bob, "hi alice", None).unwrap();
    assert_eq!(pushes.take(), vec!["alice"]);

    // Typing, receipts and locations wake nobody.
    settle(&[&alice, &bob]);
    pushes.take();
    bob.set_typing(&chat_for_bob, true).unwrap();
    bob.mark_read(&chat_for_bob).unwrap();
    bob.start_sharing_location(&chat_for_bob, None).unwrap();
    bob.update_location(fix(52.5, 13.4)).unwrap();
    settle(&[&alice, &bob]);
    assert!(pushes.take().is_empty());

    // Someone added to a group can wake everyone, and be woken.
    let group = alice.create_group("Trip", &["bob".into()]).unwrap();
    settle(&[&alice, &bob]);
    alice.add_members(&group, &["carol".into()]).unwrap();
    settle(&[&alice, &bob, &carol]);
    pushes.take();
    let group_for_carol =
        carol.conversations().unwrap().into_iter().find(|c| c.kind == ConversationKind::Group).unwrap().id;
    carol.send_text(&group_for_carol, "hello both", None).unwrap();
    assert_eq!(pushes.take(), vec!["alice", "bob"]);
    alice.send_text(&group, "welcome carol", None).unwrap();
    assert_eq!(pushes.take(), vec!["bob", "carol"]);

    // Someone who turns notifications off isn't woken any more.
    bob.clear_push_target().unwrap();
    settle(&[&alice, &bob, &carol]);
    pushes.take();
    alice.send_text(&group, "still there?", None).unwrap();
    assert_eq!(pushes.take(), vec!["carol"]);
    alice.send_text(&chat, "bob?", None).unwrap();
    assert!(pushes.take().is_empty());

    // Whoever leaves can't be woken by the group any more.
    carol.leave_conversation(&group_for_carol).unwrap();
    settle(&[&alice, &bob, &carol]);
    pushes.take();
    alice.send_text(&group, "carol left", None).unwrap();
    assert!(pushes.take().is_empty());
}

#[test]
fn notifications_are_handed_out_once_and_not_for_read_messages() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let chat = alice.start_direct_chat("bob").unwrap();
    settle(&[&alice, &bob]);
    // Joining a chat is no notification; its first message is.
    assert!(bob.take_notifications(10).unwrap().is_empty());
    alice.send_text(&chat, "one", None).unwrap();
    alice.send_text(&chat, "two", None).unwrap();
    bob.sync(0).unwrap();
    let notes = bob.take_notifications(10).unwrap();
    assert_eq!(notes.iter().map(|n| n.text.as_str()).collect::<Vec<_>>(), vec!["one", "two"]);
    assert_eq!(notes[0].title, "alice");
    assert_eq!(notes[0].sender, "alice");
    assert!(!notes[0].is_group);
    assert_eq!(notes[0].conversation_id, only_conversation(&bob));
    assert!(bob.take_notifications(10).unwrap().is_empty());
    // Our own messages never notify.
    assert!(alice.take_notifications(10).unwrap().is_empty());

    // Read in the app first: no notification.
    alice.send_text(&chat, "three", None).unwrap();
    bob.sync(0).unwrap();
    bob.mark_read(&only_conversation(&bob)).unwrap();
    assert!(bob.take_notifications(10).unwrap().is_empty());

    // A limit leaves the rest for the next call.
    alice.send_text(&chat, "four", None).unwrap();
    alice.send_text(&chat, "five", None).unwrap();
    bob.sync(0).unwrap();
    assert_eq!(bob.take_notifications(1).unwrap()[0].text, "four");
    assert_eq!(bob.take_notifications(5).unwrap()[0].text, "five");

    // Groups are titled by their name.
    alice.create_group("Trip", &["bob".into()]).unwrap();
    settle(&[&alice, &bob]);
    let group = alice.conversations().unwrap().into_iter().find(|c| c.kind == ConversationKind::Group).unwrap().id;
    alice.send_text(&group, "packing list?", None).unwrap();
    bob.sync(0).unwrap();
    let note = &bob.take_notifications(10).unwrap()[0];
    assert_eq!((note.title.as_str(), note.sender.as_str(), note.is_group), ("Trip", "alice", true));
}

#[test]
fn removing_the_account_from_a_device_stops_its_pushes() {
    let pushes = Pushes::default();
    let net = Devices::with_push(pushes.config());
    let alice = net.register("alice");
    let bob = net.register("bob");
    bob.set_push_target(apns(2)).unwrap();
    let chat = alice.start_direct_chat("bob").unwrap();
    settle(&[&alice, &bob]);
    pushes.take();
    alice.send_text(&chat, "hi", None).unwrap();
    assert_eq!(pushes.take(), vec!["bob"]);
    bob.remove_from_device().unwrap();
    settle(&[&alice]);
    pushes.take();
    // The chat may have ended by now; either way nobody is woken.
    let _ = alice.send_text(&chat, "anyone?", None);
    assert!(pushes.take().is_empty());
}

fn looks(client: &Client) -> Vec<(String, String, String)> {
    client.profiles().unwrap().into_iter().map(|p| (p.username, p.avatar, p.ink)).collect()
}

#[test]
fn profiles_reach_everyone_including_later_members() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");

    // Set before any chat: shared when the chat starts.
    alice.set_profile("fox", "teal").unwrap();
    let group = alice.create_group("Friends", &["bob".into()]).unwrap();
    settle(&[&alice, &bob]);
    assert_eq!(looks(&bob), vec![("alice".into(), "fox".into(), "teal".into())]);

    // Set after: goes to every chat, and says something changed.
    bob.set_profile("owl", "").unwrap();
    bob.sync(0).unwrap();
    let report = alice.sync(0).unwrap();
    assert!(report.profiles_changed);
    assert!(looks(&alice).contains(&("bob".into(), "owl".into(), String::new())));

    // A newcomer can't read the past, so both share again when she joins.
    alice.add_members(&group, &["carol".into()]).unwrap();
    settle(&[&alice, &bob, &carol]);
    let mut seen = looks(&carol);
    seen.sort();
    assert_eq!(seen, vec![("alice".into(), "fox".into(), "teal".into()), ("bob".into(), "owl".into(), String::new())]);

    // Our own is kept too, and setting the same again sends nothing new.
    assert_eq!(alice.profile().unwrap().unwrap().avatar, "fox");
    alice.set_profile("fox", "teal").unwrap();
    assert!(!carol.sync(0).unwrap().profiles_changed);

    // Names are checked.
    assert!(matches!(alice.set_profile("Fox!", "teal"), Err(Error::InvalidInput(_))));
    assert!(matches!(alice.set_profile(&"x".repeat(33), ""), Err(Error::InvalidInput(_))));
}

// ---- threads and reactions ----

fn group_for(client: &Client) -> String {
    client.conversations().unwrap().into_iter().find(|c| c.kind == ConversationKind::Group).unwrap().id
}

fn thread_texts(client: &Client, conversation: &str, message: &str) -> Vec<String> {
    client.thread_messages(conversation, message, 100).unwrap().into_iter().map(|m| m.text).collect()
}

fn unread(client: &Client, conversation: &str) -> u32 {
    client.conversation(conversation).unwrap().unread
}

/// The thread under `message`, as `client` sees it in the chat.
fn thread_of(client: &Client, conversation: &str, message: &str) -> messenger_core::ThreadSummary {
    client.messages(conversation, 100).unwrap().into_iter().find(|m| m.id == message).unwrap().thread.unwrap()
}

#[test]
fn threads_keep_replies_together_and_notify_the_people_in_them() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    let group = alice.create_group("Trip", &["bob".into(), "carol".into()]).unwrap();
    settle(&[&alice, &bob, &carol]);
    let (group_b, group_c) = (group_for(&bob), group_for(&carol));
    let root = alice.send_text(&group, "Where shall we eat?", None).unwrap();
    settle(&[&alice, &bob, &carol]);
    for (client, chat) in [(&alice, &group), (&bob, &group_b), (&carol, &group_c)] {
        client.mark_read(chat).unwrap();
        client.take_notifications(100).unwrap();
    }

    let noodles = bob.reply_in_thread(&group_b, &root, "The noodle place").unwrap();
    settle(&[&alice, &bob, &carol]);

    // The reply stays out of the chat, counted under the message it answers.
    assert_eq!(texts(&carol, &group_c), vec!["Where shall we eat?"]);
    let thread = thread_of(&carol, &group_c, &root);
    assert_eq!((thread.reply_count, thread.unread_count), (1, 1));
    assert_eq!(thread.participants, vec!["bob"]);
    assert_eq!(thread_texts(&carol, &group_c, &root), vec!["Where shall we eat?", "The noodle place"]);
    // Any message in a thread opens it, and replies don't quote the first one.
    assert_eq!(thread_texts(&carol, &group_c, &noodles), thread_texts(&carol, &group_c, &root));
    assert!(carol.thread_messages(&group_c, &root, 10).unwrap()[1].reply_to.is_none());
    assert!(carol.conversation(&group_c).unwrap().last_in_thread);

    // Alice started the thread, so the reply is for her; carol isn't in it.
    assert_eq!(unread(&alice, &group), 1);
    let notes = alice.take_notifications(10).unwrap();
    assert_eq!(notes.iter().map(|n| n.text.as_str()).collect::<Vec<_>>(), vec!["The noodle place"]);
    assert_eq!(notes[0].thread_root.as_deref(), Some(root.as_str()));
    assert_eq!(unread(&carol, &group_c), 0);
    assert!(carol.take_notifications(10).unwrap().is_empty());

    // Opening the chat doesn't read the thread; opening the thread does,
    // and only then does bob hear that alice read his reply.
    alice.mark_read(&group).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(thread_of(&alice, &group, &root).unread_count, 1);
    let read_by = |c: &Client| c.thread_messages(&group_b, &root, 10).unwrap()[1].read_by.clone();
    assert!(read_by(&bob).is_empty());
    alice.mark_thread_read(&group, &root).unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(thread_of(&alice, &group, &root).unread_count, 0);
    assert_eq!(read_by(&bob), vec!["alice"]);

    // Replying to a reply goes in the same thread, and joins carol to it.
    carol.reply_in_thread(&group_c, &noodles, "Count me in").unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(thread_texts(&alice, &group, &root), vec!["Where shall we eat?", "The noodle place", "Count me in"]);
    let thread = thread_of(&alice, &group, &root);
    assert_eq!((thread.reply_count, thread.participants.clone()), (2, vec!["carol".to_string(), "bob".to_string()]));
    carol.take_notifications(10).unwrap();
    bob.reply_in_thread(&group_b, &root, "7 o'clock?").unwrap();
    settle(&[&alice, &bob, &carol]);
    assert_eq!(unread(&carol, &group_c), 1);
    assert_eq!(carol.take_notifications(10).unwrap()[0].text, "7 o'clock?");

    // Threads hang off messages we have.
    assert!(matches!(bob.reply_in_thread(&group_b, &"00".repeat(16), "?"), Err(Error::InvalidInput(_))));

    // In a 1:1 chat, a thread is always for both people.
    let chat = alice.start_direct_chat("bob").unwrap();
    settle(&[&alice, &bob]);
    let chat_b = bob.conversations().unwrap().into_iter().find(|c| c.kind == ConversationKind::Direct).unwrap().id;
    let question = bob.send_text(&chat_b, "Train or bus?", None).unwrap();
    settle(&[&alice, &bob]);
    bob.mark_read(&chat_b).unwrap();
    bob.take_notifications(10).unwrap();
    let train = alice.reply_in_thread(&chat, &question, "Train").unwrap();
    settle(&[&alice, &bob]);
    bob.reply_in_thread(&chat_b, &train, "Booked").unwrap();
    alice.mark_read(&chat).unwrap();
    alice.take_notifications(10).unwrap();
    settle(&[&alice, &bob]);
    assert_eq!(texts(&bob, &chat_b), vec!["Train or bus?"]);
    assert_eq!(unread(&bob, &chat_b), 1);
    assert_eq!(unread(&alice, &chat), 1);
    assert_eq!(alice.take_notifications(10).unwrap()[0].text, "Booked");
}

#[test]
fn replies_in_a_group_thread_wake_only_the_people_in_it() {
    let pushes = Pushes::default();
    let net = Devices::with_push(pushes.config());
    let alice = net.register("alice");
    let bob = net.register("bob");
    let carol = net.register("carol");
    for (client, byte) in [(&alice, 1), (&bob, 2), (&carol, 3)] {
        client.set_push_target(apns(byte)).unwrap();
    }
    let group = alice.create_group("Trip", &["bob".into(), "carol".into()]).unwrap();
    settle(&[&alice, &bob, &carol]);
    let (group_b, group_c) = (group_for(&bob), group_for(&carol));
    let root = alice.send_text(&group, "Who's driving?", None).unwrap();
    settle(&[&alice, &bob, &carol]);
    pushes.take();

    bob.reply_in_thread(&group_b, &root, "Me").unwrap();
    assert_eq!(pushes.take(), vec!["alice"]);
    settle(&[&alice, &bob, &carol]);
    carol.reply_in_thread(&group_c, &root, "I'll bring snacks").unwrap();
    assert_eq!(pushes.take(), vec!["alice", "bob"]);
    settle(&[&alice, &bob, &carol]);
    alice.reply_in_thread(&group, &root, "Perfect").unwrap();
    assert_eq!(pushes.take(), vec!["bob", "carol"]);
    // The chat itself still wakes everyone.
    bob.send_text(&group_b, "See you at 8", None).unwrap();
    assert_eq!(pushes.take(), vec!["alice", "carol"]);
}

#[test]
fn reactions_are_emoji_of_any_kind_but_not_words() {
    let net = Devices::new();
    let alice = net.register("alice");
    let bob = net.register("bob");
    let chat = alice.start_direct_chat("bob").unwrap();
    settle(&[&alice, &bob]);
    let chat_b = only_conversation(&bob);
    let message = bob.send_text(&chat_b, "Made it!", None).unwrap();
    settle(&[&alice, &bob]);

    for emoji in ["👍", "👍🏽", "❤️", "🏳️‍🌈", "🇮🇳", "1️⃣", "👩🏻‍❤️‍💋‍👨🏼", "🫠", "🏴󠁧󠁢󠁳󠁣󠁴󠁿"]
    {
        alice.react(&chat, &message, Some(emoji)).unwrap();
        settle(&[&alice, &bob]);
        let reactions = bob.messages(&chat_b, 10).unwrap().into_iter().find(|m| m.id == message).unwrap().reactions;
        assert_eq!(reactions.iter().map(|r| r.emoji.as_str()).collect::<Vec<_>>(), vec![emoji], "{emoji}");
    }
    for words in ["", "lol", "a👍", " 👍", "👍 ", "\u{200D}👍", "1", "#"] {
        assert!(matches!(alice.react(&chat, &message, Some(words)), Err(Error::InvalidInput(_))), "{words:?}");
    }
    alice.react(&chat, &message, None).unwrap();
    settle(&[&alice, &bob]);
    assert!(bob.messages(&chat_b, 10).unwrap().into_iter().find(|m| m.id == message).unwrap().reactions.is_empty());
}
