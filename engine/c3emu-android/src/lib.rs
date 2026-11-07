//! JNI facade for c3emu-web's stable C ABI.
//!
//! All methods must be called on one dedicated Java worker thread because the
//! upstream C ABI keeps the emulator session in thread-local state.

use jni::JNIEnv;
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jdouble, jint, jlong, jshortArray, jstring};
use std::{ptr, slice};

const FRAME_BYTES: usize = 320 * 240 * 4;

fn result_bytes() -> Vec<u8> {
    let len = c3emu_web::c3_result_len();
    if len == 0 {
        return Vec::new();
    }
    let raw = c3emu_web::c3_result_ptr();
    if raw.is_null() {
        return Vec::new();
    }
    unsafe { slice::from_raw_parts(raw, len).to_vec() }
}

fn result_text() -> String {
    String::from_utf8_lossy(&result_bytes()).into_owned()
}

fn make_string(mut env: JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(ptr::null_mut())
}

fn make_bytes(mut env: JNIEnv<'_>, value: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(value)
        .map(|value| value.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeSetFile(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    kind: jint,
    data: JByteArray<'_>,
) -> jstring {
    let result = (|| -> Result<(), String> {
        if !(0..=2).contains(&kind) {
            return Err("Unknown c3emu firmware file kind.".into());
        }
        let bytes = env.convert_byte_array(data).map_err(|e| e.to_string())?;
        if bytes.is_empty() {
            return Err("The selected firmware component is empty.".into());
        }
        let len = bytes.len();
        let target = c3emu_web::c3_alloc(len);
        if target.is_null() {
            return Err("Unable to allocate native firmware buffer.".into());
        }
        unsafe {
            ptr::copy_nonoverlapping(bytes.as_ptr(), target, len);
            c3emu_web::c3_set_file(kind as usize, target, len);
        }
        Ok(())
    })();

    make_string(env, &result.err().unwrap_or_default())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeBoot(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jstring {
    let status = c3emu_web::c3_boot();
    let message = if status == 0 { String::new() } else { result_text() };
    make_string(env, &message)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeRun(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    budget_ms: jdouble,
) -> jstring {
    let wait_ms = c3emu_web::c3_run(budget_ms.max(0.1));
    let message = if wait_ms < 0.0 { result_text() } else { String::new() };
    make_string(env, &message)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeFrameSerial(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jint {
    c3emu_web::c3_frame_serial() as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeFrame(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jbyteArray {
    let raw = c3emu_web::c3_frame();
    if raw.is_null() {
        return ptr::null_mut();
    }
    let bytes = unsafe { slice::from_raw_parts(raw, FRAME_BYTES) };
    make_bytes(env, bytes)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeDrainDirtyDisks(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jbyteArray {
    let _record_count = c3emu_web::c3_disks_dirty();
    make_bytes(env, &result_bytes())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeDrainAudio(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jshortArray {
    let count = c3emu_web::c3_audio();
    let raw = c3emu_web::c3_audio_ptr();
    let samples: &[i16] = if count == 0 || raw.is_null() {
        &[]
    } else {
        unsafe { slice::from_raw_parts(raw, count) }
    };
    match env.new_short_array(samples.len() as jint) {
        Ok(array) => {
            if !samples.is_empty() {
                let _ = env.set_short_array_region(&array, 0, samples);
            }
            array.into_raw()
        }
        Err(_) => ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeKey(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    index: jint,
    down: jint,
) {
    if (0..=255).contains(&index) {
        c3emu_web::c3_key(index as u32, if down == 0 { 0 } else { 1 });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeStats(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jstring {
    c3emu_web::c3_stats();
    make_string(env, &result_text())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeSetTime(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    unix_seconds: jdouble,
    utc_offset_seconds: jdouble,
) {
    c3emu_web::c3_set_time(unix_seconds, utc_offset_seconds);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeClearDisks(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) {
    c3emu_web::c3_disks_clear();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeSetDiskSize(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    dev: jint,
    size: jlong,
) {
    if dev >= 0 && size >= 0 {
        c3emu_web::c3_disk_set_size(dev as u32, size as f64);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeRestoreDiskChunk(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    dev: jint,
    offset: jdouble,
    disk_size: jlong,
    chunk: JByteArray<'_>,
) -> jstring {
    let result = (|| -> Result<(), String> {
        if dev < 0 || !offset.is_finite() || offset < 0.0 || disk_size < 0 {
            return Err("Invalid saved flash chunk metadata.".into());
        }
        let bytes = env.convert_byte_array(chunk).map_err(|e| e.to_string())?;
        if bytes.len() != 64 * 1024 {
            return Err("Saved flash chunk must contain exactly 64 KiB.".into());
        }
        let len = bytes.len();
        let source = c3emu_web::c3_alloc(len);
        if source.is_null() {
            return Err("Unable to allocate saved flash chunk.".into());
        }
        unsafe {
            ptr::copy_nonoverlapping(bytes.as_ptr(), source, len);
            c3emu_web::c3_disk_load_chunk(dev as u32, offset, source, len);
            c3emu_web::c3_free(source, len);
        }
        c3emu_web::c3_disk_set_size(dev as u32, disk_size as f64);
        Ok(())
    })();

    make_string(env, &result.err().unwrap_or_default())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_retromobile_world_C3EmuNative_nativeStop(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) {
    c3emu_web::c3_stop();
}
