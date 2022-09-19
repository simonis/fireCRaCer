// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

use std::collections::HashMap;
use std::fs::File;
use std::os::unix::io::{AsRawFd, FromRawFd, IntoRawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::{mem, ptr};
use std::cmp::Ordering;
use std::cmp::Ordering::{Less, Equal, Greater};

use libc::c_void;
use nix::sys::mman::{mmap, MapFlags, ProtFlags};
use nix::unistd::Pid;
use serde::Deserialize;
use userfaultfd::Uffd;
use lazy_static::lazy_static;

use vmm_sys_util::errno;
use vmm_sys_util::sock_ctrl_msg::ScmSocket;

use hole_punch::*;

/// Return the default page size of the platform, in bytes.
fn get_page_size() -> Result<usize, errno::Error> {
    match unsafe { libc::sysconf(libc::_SC_PAGESIZE) } {
        -1 => Err(errno::Error::last()),
        ps => Ok(ps as usize),
    }
}

lazy_static! {
    static ref PAGE_SIZE: usize = get_page_size().unwrap();
}

// This is the same with the one used in src/vmm.
/// This describes the mapping between Firecracker base virtual address and offset in the
/// buffer or file backend for a guest memory region. It is used to tell an external
/// process/thread where to populate the guest memory data for this range.
///
/// E.g. Guest memory contents for a region of `size` bytes can be found in the backend
/// at `offset` bytes from the beginning, and should be copied/populated into `base_host_address`.
#[derive(Clone, Debug, Deserialize)]
pub struct GuestRegionUffdMapping {
    /// Base host virtual address where the guest memory contents for this region
    /// should be copied/populated.
    pub base_host_virt_addr: u64,
    /// Region size.
    pub size: usize,
    /// Offset in the backend file/buffer where the region contents are.
    pub offset: u64,
}

struct MemRegion {
    mapping: GuestRegionUffdMapping,
    page_states: HashMap<u64, MemPageState>,
}

pub struct UffdPfHandler {
    mem_regions: Vec<MemRegion>,
    backing_buffer: *const u8,
    pub uffd: Uffd,
    // Not currently used but included to demonstrate how a page fault handler can
    // fetch Firecracker's PID in order to make it aware of any crashes/exits.
    firecracker_pid: u32,
}

#[derive(Clone, Debug)]
pub enum MemPageState {
    Uninitialized,
    FromFile,
    Removed,
    Anonymous,
}

impl UffdPfHandler {
    pub fn from_unix_stream(stream: UnixStream, data: *const u8, size: usize) -> Self {
        let mut message_buf = vec![0u8; 1024];
        let (bytes_read, file) = stream
            .recv_with_fd(&mut message_buf[..])
            .expect("Cannot recv_with_fd");
        message_buf.resize(bytes_read, 0);

        let body = String::from_utf8(message_buf).unwrap();
        let file = file.expect("Uffd not passed through UDS!");

        let mappings = serde_json::from_str::<Vec<GuestRegionUffdMapping>>(&body)
            .expect("Cannot deserialize memory mappings.");
        let memsize: usize = mappings.iter().map(|r| r.size).sum();

        // Make sure memory size matches backing data size.
        assert_eq!(memsize, size);

        let uffd = unsafe { Uffd::from_raw_fd(file.into_raw_fd()) };

        let creds: libc::ucred = get_peer_process_credentials(stream);

        let mem_regions = create_mem_regions(&mappings);

        println!("Connected to PID {}", creds.pid as u32);

        Self {
            mem_regions,
            backing_buffer: data,
            uffd,
            firecracker_pid: creds.pid as u32,
        }
    }

    pub fn update_mem_state_mappings(&mut self, start: u64, end: u64, state: &MemPageState) {
        println!("Update mapping {:x} - {:x} {:?}", start, end, state);
        for region in self.mem_regions.iter_mut() {
            for (key, value) in region.page_states.iter_mut() {
                if key >= &start && key < &end {
                    *value = state.clone();
                }
            }
        }
    }

    fn populate_from_file(&self, region: &MemRegion, page_addr: u64) -> (u64, u64) {
        let src = self.backing_buffer as u64 + region.mapping.offset + page_addr - region.mapping.base_host_virt_addr;
        let len = *PAGE_SIZE;
        // Populate a single page from backing mem-file.
        println!("Populating 0x{:x} - 0x{:x} in PID {} from 0x{:x} - 0x{:x}",
                 page_addr, page_addr + len as u64, self.firecracker_pid, src, src + len as u64);
        let ret = unsafe {
            self.uffd
                .copy(src as *const _, page_addr as *mut _, len, true)
                .expect("Uffd copy failed")
        };

        // Make sure the UFFD copied some bytes.
        assert!(ret > 0);

        return (page_addr, page_addr + len as u64);
    }

    fn zero_out(&mut self, addr: u64) -> (u64, u64) {
        let page_size = *PAGE_SIZE;

        let ret = unsafe {
            self.uffd
                .zeropage(addr as *mut _, page_size, true)
                .expect("Uffd zeropage failed")
        };
        // Make sure the UFFD zeroed out some bytes.
        assert!(ret > 0);

        println!("Zeroing 0x{:x} - 0x{:x} in PID {}", addr, addr + page_size as u64, self.firecracker_pid);

        return (addr, addr + page_size as u64);
    }

    pub fn serve_pf(&mut self, addr: *mut u8, thread_id: Pid) {
        let page_size = *PAGE_SIZE;

        // Find the start of the page that the current faulting address belongs to.
        let dst = (addr as usize & !(page_size as usize - 1)) as *mut c_void;
        let fault_page_addr = dst as u64;
        println!("Page fault from TID {} at address {:x}, page {:x}", thread_id, addr as u64, fault_page_addr);

        // Get the state of the current faulting page.
        for region in self.mem_regions.iter() {
            match region.page_states.get(&fault_page_addr) {
                // Our simple PF handler has a simple strategy:
                // There exist 4 states in which a memory page can be in:
                // 1. Uninitialized - page was never touched
                // 2. FromFile - the page is populated with content from snapshotted memory file
                // 3. Removed - MADV_DONTNEED was called due to balloon inflation
                // 4. Anonymous - page was zeroed out -> this implies that more than one page fault
                //    event was received. This can be a consequence of guest reclaiming back its
                //    memory from the host (through balloon device)
                Some(MemPageState::Uninitialized) | Some(MemPageState::FromFile) => {
                    let (start, end) = self.populate_from_file(region, fault_page_addr);
                    self.update_mem_state_mappings(start, end, &MemPageState::FromFile);
                    return;
                }
                Some(MemPageState::Removed) | Some(MemPageState::Anonymous) => {
                    let (start, end) = self.zero_out(fault_page_addr);
                    self.update_mem_state_mappings(start, end, &MemPageState::Anonymous);
                    return;
                }
                None => {
                    ();
                }
            }
        }

        panic!(
            "Could not find addr: {:?} within guest region mappings.",
            addr
        );
    }
}

fn get_peer_process_credentials(stream: UnixStream) -> libc::ucred {
    let mut creds: libc::ucred = libc::ucred {
        pid: 0,
        gid: 0,
        uid: 0,
    };
    let mut creds_size = mem::size_of::<libc::ucred>() as u32;

    let ret = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut creds as *mut _ as *mut _,
            &mut creds_size as *mut libc::socklen_t,
        )
    };
    if ret != 0 {
        panic!("Failed to get peer process credentials");
    }

    creds
}

fn create_mem_regions(mappings: &Vec<GuestRegionUffdMapping>) -> Vec<MemRegion> {
    let page_size = *PAGE_SIZE;
    let mut mem_regions: Vec<MemRegion> = Vec::with_capacity(mappings.len());

    for r in mappings.iter() {
        println!("Create MemRegions for mapping {:?}", r);
        let mapping = r.clone();
        let mut addr = r.base_host_virt_addr;
        let end_addr = r.base_host_virt_addr + r.size as u64;
        let mut page_states = HashMap::new();

        while addr < end_addr {
            page_states.insert(addr, MemPageState::Uninitialized);
            addr += page_size as u64;
        }
        mem_regions.push(MemRegion {
            mapping,
            page_states,
        });
    }

    mem_regions
}

fn segment_cmp(segment: &Segment, address: u64) -> Ordering {
    println!("==> {:?} {}", segment, address);
    if address < segment.start {
        return Greater;
    }
    if address > segment.end {
        return Less;
    }
    return Equal;
}

pub fn create_pf_handler(args: Vec<String>, verbose: bool) -> UffdPfHandler {
    let uffd_sock_path = args.get(0).expect("No socket path given");
    let mem_file_path = args.get(1).expect("No memory file given");

    let mut file = File::open(mem_file_path.clone()).expect("Cannot open memfile");
    let size = file.metadata().unwrap().len() as usize;

    let segments = file.scan_chunks().expect("Unable to scan chunks");
    if verbose {
        for segment in &segments {
            println!("{:?}", segment);
        }
        // let index = segments.binary_search_by(|s| segment_cmp(&s, 131072)).expect("Segment not found");
        // println!("{:?}", segments[index]);
    }

    // mmap a memory area used to bring in the faulting regions.
    let memfile_buffer = unsafe {
        mmap(
            ptr::null_mut(),
            size,
            ProtFlags::PROT_READ,
            MapFlags::MAP_PRIVATE,
            file.as_raw_fd(),
            0,
        )
        .expect("mmap failed")
    } as *const u8;

    // Get Uffd from UDS. We'll use the uffd to handle PFs for Firecracker.
    let listener = UnixListener::bind(&uffd_sock_path).expect("Cannot bind to socket path");

    let (stream, _) = listener.accept().expect("Cannot listen on UDS socket");

    println!("Mapping memory file {} to address 0x{:x}-0x{:x}, size = {}",
             mem_file_path, memfile_buffer as u64, memfile_buffer as u64 + size as u64, size);

    UffdPfHandler::from_unix_stream(stream, memfile_buffer, size)
}
