use crate::{
    actor::server_actor::ServerMessage, debug, info, message::Message,
};
use std::sync::mpsc::Sender;

use crate::message::MessageHandler;

pub struct LoginHandler;

impl MessageHandler<ServerMessage> for LoginHandler {
    fn get_code(&self) -> u32 {
        1
    }

    fn handle(&self, message: &mut Message, sender: Sender<ServerMessage>) {
        let response = message.read_int8();

        if response != 1 {
            let _ = sender.send(ServerMessage::LoginStatus(false));
            return;
        }

        info!("Login successful");
        let greeting = message.read_string();
        debug!("Server greeting: {:?}", greeting);
        // Then our own address as the server sees it, packed like a peer's
        // (code 3): what the internet calls us, which the host compares with
        // what its router says to spot a second NAT in front of it.
        let ip: Vec<u8> = (0..4).map(|_| message.read_int8()).collect();
        if ip.iter().any(|&b| b != 0) {
            let _ = sender.send(ServerMessage::OwnAddress(format!(
                "{}.{}.{}.{}",
                ip[3], ip[2], ip[1], ip[0]
            )));
        }

        let _ = sender.send(ServerMessage::LoginStatus(true));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::message::framed;
    use std::sync::mpsc;

    #[test]
    fn a_successful_login_reports_our_address_then_the_status() {
        let mut message = framed(|m| {
            m.write_int8(1)
                .write_string("welcome")
                // 203.0.113.7, packed little-endian as the server sends it.
                .write_raw_bytes(vec![7, 113, 0, 203]);
        });
        let (tx, rx) = mpsc::channel();
        LoginHandler.handle(&mut message, tx);
        assert!(matches!(rx.try_recv(), Ok(ServerMessage::OwnAddress(ip)) if ip == "203.0.113.7"));
        assert!(matches!(rx.try_recv(), Ok(ServerMessage::LoginStatus(true))));
    }
}
