// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//! Provides functionality for a userspace page fault handler
//! which loads the whole region from the backing memory file
//! when a page fault occurs.

use nix::poll::{poll, PollFd, PollFlags};
use std::os::unix::io::AsRawFd;
use std::error::Error;
use getopt::Opt;

use uffd::uffd_utils::{create_pf_handler, MemPageState};

fn help() {
    println!("Usage: {} [-v] [-h] <socket-path> <memory-file>", std::env::args().nth(0).expect("Must be"));
    println!(" <socket-path>: path which will be used to create domain socket for communication");
    println!("                with the firecracker process. Must not exist (will be created).");
    println!(" <memory-file>: the memory file created by a previous firecracker snapshot operation.");
    println!(" -v           : produce more verbose output.");
    println!(" -h           : print this usage information.");
    std::process::exit(0);
}

fn main() -> Result<(), Box<dyn Error>> {
    let mut args: Vec<String> = std::env::args().collect();
    if std::env::args().len() <= 1 {
        help();
    }
    let mut opts = getopt::Parser::new(&args, "vh");

    let mut verbose = false;
    loop {
        match opts.next().transpose()? {
            None => break,
            Some(opt) => match opt {
                Opt('v', None) => verbose = true,
                Opt('h', None) => help(),
                _ => unreachable!(),
            }
        }
    }

    let args = args.split_off(opts.index());

    let mut uffd_handler = create_pf_handler(args, verbose);

    let pollfd = PollFd::new(uffd_handler.uffd.as_raw_fd(), PollFlags::POLLIN);

    // Loop, handling incoming events on the userfaultfd file descriptor.
    loop {
        // See what poll() tells us about the userfaultfd.
        let nready = poll(&mut [pollfd], -1).expect("Failed to poll");

        let revents = pollfd.revents().unwrap();
        println!(
            "poll() returns: nready = {}; POLLIN = {}; POLLERR = {}",
            nready,
            revents.contains(PollFlags::POLLIN),
            revents.contains(PollFlags::POLLERR),
        );

        // Read an event from the userfaultfd.
        let event = uffd_handler
            .uffd
            .read_event()
            .expect("Failed to read uffd_msg")
            .expect("uffd_msg not ready");

        println!("Reading event {:?} from uffd", event);

        // We expect to receive either a Page Fault or Removed
        // event (if the balloon device is enabled).
        match event {
            userfaultfd::Event::Pagefault { addr, thread_id, .. } => uffd_handler.serve_pf(addr as *mut u8, thread_id),
            userfaultfd::Event::Remove { start, end } => uffd_handler.update_mem_state_mappings(
                start as *mut u8 as u64,
                end as *mut u8 as u64,
                &MemPageState::Removed,
            ),
            _ => panic!("Unexpected event on userfaultfd"),
        }
    }
}
