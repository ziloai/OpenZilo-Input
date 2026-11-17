#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--core", type=Path, required=True)
parser.add_argument("--out", type=Path, required=True)
parser.add_argument("--abis", default="arm64-v8a,x86_64")
args = parser.parse_args()
core = args.core.resolve()
out = args.out.resolve() / "generated"
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
ndk = Path(os.environ.get("ANDROID_NDK_HOME", sdk / "ndk/28.2.13676358"))
host = {"Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
abis = {"arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"), "x86_64": ("x86_64-linux-android", "x86_64-linux-android")}
if not (core / "Cargo.toml").is_file():
    parser.error("Set -PrustCoreDir to a libopenzilo checkout")
def run(*command, env=None):
    subprocess.run(command, cwd=core, env=env, check=True)
run("cargo", "build", "--locked", "--features", "cli")
target = Path(os.environ.get("CARGO_TARGET_DIR", core / "target"))
(out / "uniffi").mkdir(parents=True, exist_ok=True)
suffix = "dylib" if platform.system() == "Darwin" else "so"
run(str(target / "debug/uniffi-bindgen"), "generate", "--library", str(target / f"debug/liblibopenzilo.{suffix}"), "--language", "kotlin", "--config", str(core / "uniffi.toml"), "--out-dir", str(out / "uniffi"), "--no-format")
for abi in args.abis.split(","):
    rust, clang = abis[abi]
    linker = ndk / f"toolchains/llvm/prebuilt/{host}/bin/{clang}26-clang"
    if not linker.is_file():
        parser.error(f"Missing NDK linker: {linker}")
    env = os.environ.copy()
    env[f"CARGO_TARGET_{rust.upper().replace('-', '_')}_LINKER"] = str(linker)
    run("cargo", "build", "--locked", "--release", "--lib", "--target", rust, env=env)
    destination = out / "jniLibs" / abi
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target / rust / "release/liblibopenzilo.so", destination)
