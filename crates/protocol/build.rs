//! Compiles the .proto files under `proto/` with protox (a pure-Rust protobuf
//! compiler), so building needs no `protoc` on the machine.

use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let proto_root = manifest_dir.join("../../proto");
    let file = proto_root.join("messenger/v1/content.proto");
    println!("cargo:rerun-if-changed={}", file.display());

    let descriptors = protox::compile([&file], [&proto_root]).expect("compile .proto files");
    prost_build::Config::new().compile_fds(descriptors).expect("generate Rust code from .proto files");
}
