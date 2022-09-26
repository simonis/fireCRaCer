// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

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
}

pub struct UffdPfHandler {
    mem_regions: Vec<MemRegion>,
    backing_buffer: *const u8,
    pub uffd: Uffd,
    // Not currently used but included to demonstrate how a page fault handler can
    // fetch Firecracker's PID in order to make it aware of any crashes/exits.
    _firecracker_pid: u32,
    mem_segments: Vec<Segment>,
}

#[derive(Clone, Debug)]
pub enum MemPageState {
    Uninitialized,
    FromFile,
    Removed,
    Anonymous,
}

impl UffdPfHandler {
    pub fn from_unix_stream(stream: UnixStream, data: *const u8, size: usize, mem_segments: Vec<Segment>) -> Self {
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
            _firecracker_pid: creds.pid as u32,
            mem_segments,
        }
    }

    pub fn serve_remove(&mut self, start: u64, end: u64) {
        // This is not really supported for now and I'm not sure how to do that.
        // The problem is that UFFD_EVENT_REMOVE can be triggered by both,
        // MADV_DONTNEED and MADV_REMOVE and there's no way to know it here.
        // Even if we knew which one triggered the UFFD_EVENT_REMOVE event,
        // we can't know if that was for shared mapping or a private mapping.
        // For the first case we would have to bring in the orignal  or even updated
        // memory content in that range, for the second one we could simply
        // zero out the pages (as we should also do for MADV_REMOVE).
        // By doing nothing here we basically fall back to always returning
        // the original content from the backup file.
        println!("UFFD_EVENT_REMOVE: {:#018x} - {:#018x}", start, end);
    }

    fn populate_from_file(&self, host_virt_addr: u64, guest_phys_addr: u64) {
        let src = self.backing_buffer as u64 + guest_phys_addr;
        // Populate a single page from backing mem-file.
        println!(" Loading: {:#018x} - {:#018x}", guest_phys_addr, guest_phys_addr + *PAGE_SIZE as u64);
        let ret = unsafe {
            self.uffd
                .copy(src as *const _, host_virt_addr as *mut _, *PAGE_SIZE, true)
                .expect("Uffd copy failed")
        };
        // Make sure the UFFD copied some bytes.
        assert!(ret > 0);
    }

    fn zero_out(&self, addr: u64, guest_phys_addr: u64) {
        println!(" Zeroing: {:#018x} - {:#018x}", guest_phys_addr, guest_phys_addr + *PAGE_SIZE as u64);
        let ret = unsafe {
            self.uffd
                .zeropage(addr as *mut _, *PAGE_SIZE, true)
                .expect("Uffd zeropage failed")
        };
        // Make sure the UFFD zeroed out some bytes.
        assert!(ret > 0);
    }

    // We don't use _thread_id for now. First, it requires a patched version of Firecracker which sets
    // the 'UFFD_FEATURE_THREAD_ID' feature (i.e. 'uffd_builder.require_features(FeatureFlags::THREAD_ID)').
    // Second, the thread IDs are not meaningfull if the process which generates the page faults is a KVM
    // container like firecracker, because the thread guests are not visible on the host.
    pub fn serve_pf(&mut self, addr: *mut u8, write: bool, _thread_id: Pid) {
        // Find the start of the page that the current faulting address belongs to.
        let dst = (addr as usize & !(*PAGE_SIZE as usize - 1)) as *mut c_void;
        let fault_page_addr = dst as u64;
        let access = if write {
            "w"
        } else {
            "r"
        }.to_string();
        print!("UFFD_EVENT_PAGEFAULT ({}): {:#018x} {:#018x} ", access, addr as u64, fault_page_addr);

        for region in self.mem_regions.iter() {
            if fault_page_addr >= region.mapping.base_host_virt_addr &&
                fault_page_addr < region.mapping.base_host_virt_addr + region.mapping.size as u64 {

                let guest_addr = region.mapping.offset + (fault_page_addr - region.mapping.base_host_virt_addr);

                let index = self.mem_segments.binary_search_by(|s| segment_cmp(&s, guest_addr as u64)).expect("Segment not found");
                if self.mem_segments[index].is_data() {
                    self.populate_from_file(fault_page_addr, guest_addr);
                } else {
                    self.zero_out(fault_page_addr, guest_addr);
                }
                return;
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
    let mut mem_regions: Vec<MemRegion> = Vec::with_capacity(mappings.len());
    for r in mappings.iter() {
        println!("Create MemRegions for mapping {:?}", r);
        let mapping = r.clone();

        mem_regions.push(MemRegion {
            mapping,
        });
    }

    mem_regions
}

fn segment_cmp(segment: &Segment, address: u64) -> Ordering {
    // println!("==> {:?} {}", segment, address);
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

    println!("Mapping memory file {} to address {:#018x} - {:#018x}, size = {}",
             mem_file_path, memfile_buffer as u64, memfile_buffer as u64 + size as u64, size);

    let segments = file.scan_chunks().expect("Unable to scan chunks");
    if verbose {
        for segment in &segments {
            println!("{:?}", segment);
        }
    }

    UffdPfHandler::from_unix_stream(stream, memfile_buffer, size, segments)
}
