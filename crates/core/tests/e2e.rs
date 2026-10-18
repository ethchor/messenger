//! End-to-end tests: real clients against a real server on a random port.

use std::time::Duration;

use messenger_core::{Client, ConversationKind, Error, MessageStatus};
use tempfile::TempDir;

struct TestServer {
    url: String,
    _runtime: tokio::runtime::Runtime,
    _dir: TempDir,
}

fn start_server() -> TestServer {
    let dir = tempfile::tempdir().unwrap();
    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
    let config =
        messenger_server::Config { db_path: dir.path().join("server.db"), retention: Duration::from_secs(3600) };
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
