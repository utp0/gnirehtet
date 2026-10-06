/*
 * Copyright (C) 2017 Genymobile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

use clap::{App, Arg};

pub const PARAM_NONE: u8 = 0;
pub const PARAM_SERIAL: u8 = 1;
pub const PARAM_DNS_SERVERS: u8 = 1 << 1;
pub const PARAM_ROUTES: u8 = 1 << 2;
pub const PARAM_PORT: u8 = 1 << 3;
pub const PARAM_WHITELIST_BUNDLE_IDS: u8 = 1 << 4;
pub const PARAM_STOP_ON_DISCONNECT: u8 = 1 << 5;
pub const PARAM_CONF: u8 = 1 << 6;

pub const DEFAULT_PORT: u16 = 31416;

pub struct CommandLineArguments {
    serial: Option<String>,
    dns_servers: Option<String>,
    routes: Option<String>,
    port: u16,
    whitelist_bundle_ids: Option<String>,
    stop_on_disconnect: bool,
    conf: String,
}

impl CommandLineArguments {
    // simple String as errors is sufficient, we never need to inspect them
    pub fn parse<S: Into<String>>(accepted_parameters: u8, args: Vec<S>) -> Result<Self, String> {
        let mut app = App::new("gnirehtet");
        if (accepted_parameters & PARAM_SERIAL) != 0 {
            app = app.arg(Arg::with_name("serial").index(1).help("The device serial"));
        }
        if (accepted_parameters & PARAM_DNS_SERVERS) != 0 {
            app = app.arg(Arg::with_name("dns").short("d").takes_value(true).value_name("DNS"));
        }
        if (accepted_parameters & PARAM_ROUTES) != 0 {
            app = app.arg(Arg::with_name("routes").short("r").takes_value(true).value_name("ROUTE"));
        }
        if (accepted_parameters & PARAM_PORT) != 0 {
            app = app.arg(Arg::with_name("port").short("p").takes_value(true).value_name("PORT"));
        }
        if (accepted_parameters & PARAM_WHITELIST_BUNDLE_IDS) != 0 {
            app = app.arg(Arg::with_name("whitelist").short("b").takes_value(true).value_name("BUNDLE_ID"));
        }
        if (accepted_parameters & PARAM_STOP_ON_DISCONNECT) != 0 {
            app = app.arg(Arg::with_name("stop_on_disconnect").short("s"));
        }
        if (accepted_parameters & PARAM_CONF) != 0 {
            app = app.arg(Arg::with_name("conf").short("c").takes_value(true).value_name("CONF"));
        }

        let mut argv: Vec<String> = vec!["gnirehtet".to_string()];
        argv.extend(args.into_iter().map(Into::into));

        let matches = match app.get_matches_from_safe(argv) {
            Ok(matches) => matches,
            Err(err) => return Err(err.message),
        };

        let port = match matches.value_of("port") {
            Some(value) => match value.parse::<u16>() {
                Ok(port) if port != 0 => port,
                _ => return Err(format!("Invalid port: {}", value)),
            },
            None => DEFAULT_PORT,
        };

        Ok(Self {
            serial: matches.value_of("serial").map(String::from),
            dns_servers: matches.value_of("dns").map(String::from),
            routes: matches.value_of("routes").map(String::from),
            port,
            whitelist_bundle_ids: matches.value_of("whitelist").map(String::from),
            stop_on_disconnect: matches.is_present("stop_on_disconnect"),
            conf: matches.value_of("conf").unwrap_or("").to_string(),
        })
    }

    pub fn serial(&self) -> Option<&str> {
        self.serial.as_deref()
    }

    pub fn dns_servers(&self) -> Option<&str> {
        self.dns_servers.as_deref()
    }

    pub fn routes(&self) -> Option<&str> {
        self.routes.as_deref()
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn stop_on_disconnect(&self) -> bool {
        self.stop_on_disconnect
    }

    pub fn whitelist_bundle_ids(&self) -> Option<&str> {
        self.whitelist_bundle_ids.as_deref()
    }

    pub fn conf(&self) -> &str {
        &self.conf
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ACCEPT_ALL: u8 =
        PARAM_SERIAL | PARAM_DNS_SERVERS | PARAM_ROUTES | PARAM_WHITELIST_BUNDLE_IDS | PARAM_STOP_ON_DISCONNECT;

    #[test]
    fn test_no_args() {
        let args = CommandLineArguments::parse(ACCEPT_ALL, Vec::<&str>::new()).unwrap();
        assert!(args.serial.is_none());
        assert!(args.dns_servers.is_none());
        assert!(args.whitelist_bundle_ids.is_none());
    }

    #[test]
    fn test_serial_only() {
        let raw_args = vec!["myserial"];
        let args = CommandLineArguments::parse(ACCEPT_ALL, raw_args).unwrap();
        assert_eq!("myserial", args.serial.unwrap());
    }

    #[test]
    fn test_invalid_paramater() {
        let raw_args = vec!["myserial", "other"];
        assert!(CommandLineArguments::parse(ACCEPT_ALL, raw_args).is_err());
    }

    #[test]
    fn test_dns_servers_only() {
        let raw_args = vec!["-d", "8.8.8.8"];
        let args = CommandLineArguments::parse(ACCEPT_ALL, raw_args).unwrap();
        assert!(args.serial.is_none());
        assert_eq!("8.8.8.8", args.dns_servers.unwrap());
    }

    #[test]
    fn test_serial_and_dns_servers() {
        let raw_args = vec!["myserial", "-d", "8.8.8.8"];
        let args = CommandLineArguments::parse(ACCEPT_ALL, raw_args).unwrap();
        assert_eq!("myserial", args.serial.unwrap());
        assert_eq!("8.8.8.8", args.dns_servers.unwrap());
    }

    #[test]
    fn test_dns_servers_and_serial() {
        let raw_args = vec!["-d", "8.8.8.8", "myserial"];
        let args = CommandLineArguments::parse(ACCEPT_ALL, raw_args).unwrap();
        assert_eq!("myserial", args.serial.unwrap());
        assert_eq!("8.8.8.8", args.dns_servers.unwrap());
    }

    #[test]
    fn test_serial_with_no_dns_servers_parameter() {
        let raw_args = vec!["myserial", "-d"];
        assert!(CommandLineArguments::parse(ACCEPT_ALL, raw_args).is_err());
    }

    #[test]
    fn test_no_dns_servers_parameter() {
        let raw_args = vec!["-d"];
        assert!(CommandLineArguments::parse(ACCEPT_ALL, raw_args).is_err());
    }

    #[test]
    fn test_routes_parameter() {
        let raw_args = vec!["-r", "1.2.3.0/24"];
        let args = CommandLineArguments::parse(ACCEPT_ALL, raw_args).unwrap();
        assert_eq!("1.2.3.0/24", args.routes.unwrap());
    }

    #[test]
    fn test_no_routes_parameter() {
        let raw_args = vec!["-r"];
        assert!(CommandLineArguments::parse(ACCEPT_ALL, raw_args).is_err());
    }

    #[test]
    fn test_bundle_id_parameter() {
        let raw_args = vec!["-b", "com.myapp.xyz"];
        let args = CommandLineArguments::parse(ACCEPT_ALL, raw_args).unwrap();
        assert_eq!("com.myapp.xyz", args.whitelist_bundle_ids.unwrap());
    }

    #[test]
    fn test_no_bundle_id_parameter() {
        let raw_args = vec!["-b"];
        assert!(CommandLineArguments::parse(ACCEPT_ALL, raw_args).is_err());
    }

    #[test]
    fn test_stop_on_disconnect_parameter() {
        let raw_args = vec!["-s"];
        let args = CommandLineArguments::parse(ACCEPT_ALL, raw_args).unwrap();
        assert!(args.stop_on_disconnect())
    }

    #[test]
    fn test_no_stop_on_disconnect_parameter() {
        let raw_args = Vec::<&str>::new();
        let args = CommandLineArguments::parse(ACCEPT_ALL, raw_args).unwrap();
        assert!(!args.stop_on_disconnect())
    }
}
